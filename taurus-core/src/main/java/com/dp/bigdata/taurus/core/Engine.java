package com.dp.bigdata.taurus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.dp.bigdata.taurus.generated.mapper.HostMapper;
import com.dp.bigdata.taurus.generated.mapper.TaskAttemptMapper;
import com.dp.bigdata.taurus.generated.mapper.TaskMapper;
import com.dp.bigdata.taurus.generated.module.Host;
import com.dp.bigdata.taurus.generated.module.Task;
import com.dp.bigdata.taurus.generated.module.TaskAttempt;
import com.dp.bigdata.taurus.generated.module.TaskAttemptExample;
import com.dp.bigdata.taurus.generated.module.TaskExample;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecuteException;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecuteStatus;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecutorManager;
import com.dp.bigdata.taurus.zookeeper.heartbeat.helper.AgentHandler;
import com.dp.bigdata.taurus.zookeeper.heartbeat.helper.AgentMonitor;

/**
 * Engine is the default implementation of the <code>Scheduler</code>.
 * 
 * @author damon.zhu
 * @see Scheduler
 */
final public class Engine implements Scheduler {

    private static final Log LOG = LogFactory.getLog(Engine.class);

    private final Map<String, Task> registedTasks; // Map<taskID, task>
    private final Map<String, String> tasksMapCache; // Map<name, taskID>
    private final Map<String, HashMap<String, AttemptContext>> runningAttempts; // Map<taskID,HashMap<attemptID,AttemptContext>>
    private Runnable progressMonitor;
    @Autowired
    @Qualifier("triggle.crontab")
    private Triggle crontabTriggle;
    @Autowired
    @Qualifier("triggle.dependency")
    private Triggle dependencyTriggle;
    @Autowired
    @Qualifier("filter.isAllowMutilInstance")
    private Filter filter;
    @Autowired
    private TaskAssignPolicy assignPolicy;
    @Autowired
    private TaskAttemptMapper taskAttemptMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private IDFactory idFactory;
    @Autowired
    private ExecutorManager zookeeper;
    @Autowired
    private HostMapper hostMapper;
    @Autowired
    private AgentMonitor agentMonitor;

    /**
     * Maximum concurrent running attempt number
     */
    private int maxConcurrency = 20;

    public Engine() {
        registedTasks = new ConcurrentHashMap<String, Task>();
        tasksMapCache = new ConcurrentHashMap<String, String>();
        runningAttempts = new ConcurrentHashMap<String, HashMap<String, AttemptContext>>();
    }

    /**
     * load data from the database;
     */
    public void load() {
        // load all tasks
        TaskExample example = new TaskExample();
        example.or().andStatusEqualTo(TaskStatus.RUNNING);
        example.or().andStatusEqualTo(TaskStatus.SUSPEND);
        List<Task> tasks = taskMapper.selectByExample(example);
        for (Task task : tasks) {
            registedTasks.put(task.getTaskid(), task);
            tasksMapCache.put(task.getName(), task.getTaskid());
        }
        // load running attempts
        TaskAttemptExample example1 = new TaskAttemptExample();
        example1.or().andStatusEqualTo(AttemptStatus.RUNNING);
        List<TaskAttempt> attempts = taskAttemptMapper.selectByExample(example1);
        for (TaskAttempt attempt : attempts) {
            Task task = registedTasks.get(attempt.getTaskid());
            AttemptContext context = new AttemptContext(attempt, task);
            registAttemptContext(context);
        }
    }

    /**
     * start the engine;
     */
    public void start() {
        new Thread(progressMonitor).start();

        agentMonitor.agentMonitor(new AgentHandler() {

            @Override
            public void disConnected(String ip) {
                Host host = new Host();
                host.setName(ip);
                host.setIp(ip);
                host.setIsconnected(false);
                hostMapper.updateByPrimaryKeySelective(host);
            }

            @Override
            public void connected(String ip) {
                Host host = hostMapper.selectByPrimaryKey(ip);
                Host newHost = new Host();
                newHost.setIp(ip);
                newHost.setName(ip);
                newHost.setIsconnected(true);
                if (host == null) {
                    newHost.setPoolid(1);
                    hostMapper.insert(newHost);
                } else {
                    hostMapper.updateByPrimaryKeySelective(newHost);
                }
            }
        });

        while (true) {
            LOG.info("Engine trys to scan the database...");
            List<AttemptContext> contexts = null;
            try {
                crontabTriggle.triggle();
                dependencyTriggle.triggle();
                contexts = filter.filter(getReadyToRunAttempt());
            } catch (Exception e) {
                LOG.error("Unexpected Exception", e);
            }
            if (contexts != null) {
                for (AttemptContext context : contexts) {
                    try {
                        executeAttempt(context);
                    } catch (ScheduleException e) {
                        // do nothing
                        LOG.error(e.getMessage());
                    } catch (Exception e) {
                        LOG.error("Unexpected Exception", e);
                    }
                }
            }
            try {
                Thread.sleep(SCHDUELE_INTERVAL);
            } catch (InterruptedException e) {
                LOG.error("Interrupted exception", e);
            }
        }
    }

    /**
     * graceful shutdown the server;
     */
    public void stop() {
        // TODO Auto-generated method stub

    }

    @Override
    public synchronized void registerTask(Task task) throws ScheduleException {
        if (!registedTasks.containsKey(task.getTaskid())) {
            taskMapper.insertSelective(task);
            task = taskMapper.selectByPrimaryKey(task.getTaskid());
            registedTasks.put(task.getTaskid(), task);
            tasksMapCache.put(task.getName(), task.getTaskid());
        } else {
            throw new ScheduleException("The task : " + task.getTaskid() + " has been registered.");
        }
    }

    @Override
    public synchronized void unRegisterTask(String taskID) throws ScheduleException {
        Map<String, AttemptContext> contexts = runningAttempts.get(taskID);
        if (contexts != null && contexts.size() > 0) {
            throw new ScheduleException("There are running attempts, so cannot remove this task");
        }

        if (registedTasks.containsKey(taskID)) {
            Task task = registedTasks.get(taskID);
            task.setStatus(TaskStatus.DELETED);
            taskMapper.updateByPrimaryKeySelective(task);
            registedTasks.remove(taskID);
            tasksMapCache.remove(task.getName());
        }
    }

    @Override
    public synchronized void updateTask(Task task) throws ScheduleException {
        if (registedTasks.containsKey(task.getTaskid())) {
            Task origin = registedTasks.get(task.getTaskid());
            task.setUpdatetime(new Date());
            task.setStatus(origin.getStatus());
            task.setCreator(null);
            taskMapper.updateByPrimaryKeySelective(task);
            registedTasks.remove(task.getTaskid());
            Task tmp = taskMapper.selectByPrimaryKey(task.getTaskid());
            registedTasks.put(task.getTaskid(), tmp);
        } else {
            throw new ScheduleException("The task : " + task.getTaskid() + " has not been found.");
        }
    }

    @Override
    public synchronized void executeTask(String taskID, long timeout) throws ScheduleException {
        //TODO timeout
        String instanceID = idFactory.newInstanceID(taskID);
        TaskAttempt attempt = new TaskAttempt();
        String attemptID = idFactory.newAttemptID(instanceID);
        attempt.setInstanceid(instanceID);
        attempt.setTaskid(taskID);
        attempt.setStatus(AttemptStatus.UNKNOWN);
        attempt.setAttemptid(attemptID);
        attempt.setScheduletime(new Date());
        taskAttemptMapper.insertSelective(attempt);
        Task task = registedTasks.get(taskID);
        AttemptContext context = new AttemptContext(attempt, task);
        executeAttempt(context);
    }

    @Override
    public synchronized void suspendTask(String taskID) throws ScheduleException {
        if (registedTasks.containsKey(taskID)) {
            Task task = registedTasks.get(taskID);
            task.setStatus(TaskStatus.SUSPEND);
            task.setUpdatetime(new Date());
            taskMapper.updateByPrimaryKeySelective(task);
        } else {
            throw new ScheduleException("The task : " + taskID + " has not been found.");
        }
    }

    @Override
    public void resumeTask(String taskID) throws ScheduleException {
        if (registedTasks.containsKey(taskID)) {
            Task task = registedTasks.get(taskID);
            task.setStatus(TaskStatus.RUNNING);
            Date current = new Date();
            task.setLastscheduletime(current);
            task.setUpdatetime(current);
            taskMapper.updateByPrimaryKeySelective(task);
        } else {
            throw new ScheduleException("The task : " + taskID + " has not been found.");
        }
    }

    public synchronized void executeAttempt(AttemptContext context) throws ScheduleException {
        TaskAttempt attempt = context.getAttempt();
        Task task = context.getTask();
        Host host;
        if (task.getPoolid() == 1) {
            host = new Host();
            //TODO: assume that hostname is ip address!!
            host.setIp(task.getHostname());
            //host = hostMapper.selectByPrimaryKey(task.getHostname());
        } else {
            host = assignPolicy.assignTask(task);
        }
        attempt.setExechost(host.getIp());
        attempt.setStarttime(new Date());
        final long start = System.nanoTime();
        try {
            zookeeper.execute(context.getContext());
        } catch (ExecuteException ee) {
            attempt.setStatus(AttemptStatus.SUBMIT_FAIL);
            attempt.setEndtime(new Date());
            taskAttemptMapper.updateByPrimaryKeySelective(attempt);
            throw new ScheduleException("Fail to execute attemptID : " + attempt.getAttemptid() + " on host : "
                    + host.getIp(),ee);
        }
        final long end = System.nanoTime();
        LOG.info("Time (seconds) taken " + (end - start) / 1.0e9 + " to start attempt : " + context.getAttemptid());

        // update the status for TaskAttempt
        attempt.setStatus(AttemptStatus.RUNNING);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        // register the attempt context
        registAttemptContext(context);
    }

    @Override
    public boolean isRuningAttempt(String attemptID) {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = contexts.get(attemptID);
        if (context == null) {
            return false;
        } else {
            return true;
        }

    }

    @Override
    public synchronized void killAttempt(String attemptID) throws ScheduleException {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = contexts.get(attemptID);
        if (context == null) {
            throw new ScheduleException("Unable find attemptID : " + attemptID);
        }
        try {
            zookeeper.kill(context.getContext());
        } catch (ExecuteException ee) {
            // do nothing; keep the attempt status unchanged.
            throw new ScheduleException("Fail to execute attemptID : " + attemptID + " on host : " + context.getExechost());
        }
        context.getAttempt().setStatus(AttemptStatus.KILLED);
        context.getAttempt().setEndtime(new Date());
        taskAttemptMapper.updateByPrimaryKeySelective(context.getAttempt());
        unregistAttemptContext(context);
    }

    @Override
    public void attemptSucceed(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setReturnvalue(0);
        attempt.setEndtime(new Date());
        attempt.setStatus(AttemptStatus.SUCCEEDED);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        unregistAttemptContext(context);
    }

    @Override
    public void attemptExpired(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setEndtime(new Date());
        attempt.setStatus(AttemptStatus.TIMEOUT);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
    }

    @Override
    public void attemptFailed(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setStatus(AttemptStatus.FAILED);
        attempt.setEndtime(new Date());
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        unregistAttemptContext(context);

        /*
         * Check whether it is necessary to retry this failed attempt. If true, insert new attempt into the database; Otherwise, do
         * nothing.
         */
        Task task = context.getTask();
        if (task.getIsautoretry()) {
            TaskAttemptExample example = new TaskAttemptExample();
            example.or().andInstanceidEqualTo(attempt.getInstanceid());
            List<TaskAttempt> attemptsOfRecentInstance = taskAttemptMapper.selectByExample(example);
            if (task.getRetrytimes() < attemptsOfRecentInstance.size() - 1) {
                //do nothing
            } else if (task.getRetrytimes() == attemptsOfRecentInstance.size() - 1) {
                //do nothing
            } else {
                LOG.info("Attempt " + attempt.getAttemptid() + " fail, begin to retry the attempt...");
                String instanceID = attempt.getInstanceid();
                TaskAttempt retry = new TaskAttempt();
                String id = idFactory.newAttemptID(instanceID);
                retry.setAttemptid(id);
                retry.setTaskid(task.getTaskid());
                retry.setInstanceid(instanceID);
                retry.setScheduletime(attempt.getScheduletime());
                retry.setStatus(AttemptStatus.DEPENDENCY_PASS);
                taskAttemptMapper.insertSelective(retry);
            }
        }
    }

    
	public void attemptUnKonwed(String attemptID){
		AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setEndtime(new Date());
        attempt.setStatus(AttemptStatus.UNKNOWN);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        unregistAttemptContext(context);
	}
	
    @Override
    public List<AttemptContext> getAllRunningAttempt() {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        for (HashMap<String, AttemptContext> maps : runningAttempts.values()) {
            for (AttemptContext context : maps.values()) {
                contexts.add(context);
            }
        }
        return Collections.unmodifiableList(contexts);
    }

    @Override
    public List<AttemptContext> getRunningAttemptsByTaskID(String taskID) {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        HashMap<String, AttemptContext> maps = runningAttempts.get(taskID);
        if (maps == null) {
            return contexts;
        }
        for (AttemptContext context : runningAttempts.get(taskID).values()) {
            contexts.add(context);
        }
        return Collections.unmodifiableList(contexts);
    }

    @Override
    public AttemptStatus getAttemptStatus(String attemptID) {
        HashMap<String, AttemptContext> maps = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = maps.get(attemptID);
        ExecuteStatus status = null;
        try {
            status = zookeeper.getStatus(context.getContext());
        } catch (ExecuteException ee) {
      	  // 当心跳节点消失后出现异常，但是作业仍应该是running状态。
            status = new ExecuteStatus(AttemptStatus.RUNNING);
        }
        AttemptStatus astatus = new AttemptStatus(status.getStatus());
        astatus.setReturnCode(status.getReturnCode());
        return astatus;
    }

    private List<AttemptContext> getReadyToRunAttempt() {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        TaskAttemptExample example = new TaskAttemptExample();
        example.or().andStatusEqualTo(AttemptStatus.DEPENDENCY_PASS);
        example.setOrderByClause("scheduleTime");
        List<TaskAttempt> attempts = taskAttemptMapper.selectByExample(example);
        for (TaskAttempt attempt : attempts) {
            Task task = registedTasks.get(attempt.getTaskid());
            contexts.add(new AttemptContext(attempt, task));
        }
        return contexts;
    }

    private void registAttemptContext(AttemptContext context) {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(context.getTaskid());
        if (contexts == null) {
            contexts = new HashMap<String, AttemptContext>();
        }
        contexts.put(context.getAttemptid(), context);
        runningAttempts.put(context.getTaskid(), contexts);
    }

    private void unregistAttemptContext(AttemptContext context) {
        if (runningAttempts.containsKey(context.getTaskid())) {
            HashMap<String, AttemptContext> contexts = runningAttempts.get(context.getTaskid());
            if (contexts.containsKey(context.getAttemptid())) {
                contexts.remove(context.getAttemptid());
            }
        }
    }

    @Override
    public Map<String, Task> getAllRegistedTask() {
        return Collections.unmodifiableMap(registedTasks);
    }

    @Override
    public synchronized Task getTaskByName(String name) throws ScheduleException {
        if (tasksMapCache.containsKey(name)) {
            String taskID = tasksMapCache.get(name);
            Task task = registedTasks.get(taskID);
            if(task == null ){
                throw new ScheduleException("Cannot found tasks for the given name.");
            } else {
                return task;
            }
        } else {
            throw new ScheduleException("Cannot found tasks for the given name.");
        }
    }

    public Runnable getProgressMonitor() {
        return progressMonitor;
    }

    public void setProgressMonitor(Runnable progressMonitor) {
        this.progressMonitor = progressMonitor;
    }

    @Override
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }
}
