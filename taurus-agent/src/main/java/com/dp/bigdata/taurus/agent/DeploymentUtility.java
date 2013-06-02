package com.dp.bigdata.taurus.agent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;

import com.dp.bigdata.taurus.agent.utils.AgentEnvValue;
import com.dp.bigdata.taurus.agent.utils.AgentServerHelper;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.bean.DeploymentConf;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.bean.DeploymentStatus;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.interfaces.DeploymentInfoChannel;



public class DeploymentUtility {

	private static final Log s_logger = LogFactory.getLog(DeploymentUtility.class);
	private static Map<String, Lock> taskIdToLockMap = new HashMap<String, Lock>();

//	private static final String UNDEPLOYMENT_CMD = "rm -rf %s";
//	private static String taskDeploy = "/script/task-deploy.sh";

	private static String deployPath;
	private static ExecutorService threadPool;
	private static TaskHelper taskDeployer = new TaskHelper();
	
	static{
		threadPool = AgentServerHelper.createThreadPool(2, 4);
//		String path = AgentEnvValue.getValue(AgentEnvValue.AGENT_ROOT_PATH,"/data/app/taurus-agent");
//		taskDeploy = path + taskDeploy;
		deployPath = AgentEnvValue.getValue(AgentEnvValue.JOB_PATH);
	}

	private static Lock getLock(String taskId){
		synchronized(taskIdToLockMap){
			Lock lock = taskIdToLockMap.get(taskId);
			if(lock == null){
				lock = new ReentrantLock();
				taskIdToLockMap.put(taskId, lock);
			}
			return lock;
		}
	}
	
	public static void checkAndUndeployTasks( String localIp, DeploymentInfoChannel cs, boolean addWatcher) {
		s_logger.debug("Start checkAndUndeployTasks");
		Watcher watcher = null;
		if(addWatcher) {
			watcher = new TaskUndeployWatcher( localIp, cs);
		} 
		Set<String> currentNew = cs.getNewUnDeploymentTaskIds(localIp, watcher);
		for(String task: currentNew){
			Runnable undeploymentThread = new UndeploymentThread( localIp, cs, task);
			threadPool.submit(undeploymentThread);
		}
		s_logger.debug("End checkAndUndeployTasks");
	}

	public static void checkAndDeployTasks(String localIp, DeploymentInfoChannel cs, boolean addWatcher) {
		s_logger.debug("Start checkAndDeployTasks");
		Watcher watcher = null;
		if(addWatcher) {
			watcher = new TaskDeployWatcher( localIp, cs);
		} 
		Set<String> currentNew = cs.getNewDeploymentTaskIds(localIp, watcher);		
//		Set<String> newAddedJTIds = AgentServerHelper.getNewAddedJobs(previousJTs, currentNew);
		for(String task: currentNew){
			Runnable deploymentThread = new DeploymentThread(localIp, cs, task);
			threadPool.submit(deploymentThread);
		}
		s_logger.debug("End checkAndDeployTasks");
	}
	
	private static final class DeploymentThread implements Runnable{
		
//		Executor executor;
		String localIp;
		DeploymentInfoChannel cs;
		String task;

		DeploymentThread( String localIp, DeploymentInfoChannel cs, String task){
			this.localIp = localIp;
			this.cs = cs;
			this.task = task;
		}
		
		@Override
		public void run() {
			s_logger.debug("start deploy");
			Lock lock = getLock(task);
			try{
				lock.lock();
				DeploymentConf conf = (DeploymentConf) cs.getConf(localIp, task);
				DeploymentStatus status = (DeploymentStatus) cs.getStatus(localIp, task, null);
				if(status == null || status.getStatus() == DeploymentStatus.DEPLOY_SUCCESS){
					return;
				}

				deployTask(conf, status);
				cs.updateStatus(localIp, task, status);
				cs.updateConf(localIp, task, conf);
			} finally{
				lock.unlock();
			}
		}
		
		private void deployTask(DeploymentConf conf, DeploymentStatus status){
			if(conf == null){
				s_logger.error("DeploymentConf is empty!");
				return;
			}

			String hdfsPath = conf.getHdfsPath();
			if(hdfsPath == null){
				s_logger.error("HDFS path is empty!");
				return;
			}
			String taskID = conf.getTaskID();

			if(taskID == null){
				s_logger.error("Job  ID is empty!");
				return;
			}
			File hdfsFile = new File(hdfsPath);
			String fileName = hdfsFile.getName();
			String localParentPath = deployPath  + File.separator + taskID;
			String localPath = localParentPath + File.separator + fileName;
			
			StringBuilder stdErr = new StringBuilder();
			try{
				if(new File(localParentPath).exists()) {
					//executor.execute("remove task",System.out, System.err, String.format(UNDEPLOYMENT_CMD, localParentPath));
				    FileUtils.deleteDirectory(new File(localParentPath));
				}
				s_logger.debug("hdfsPath:" + hdfsPath + ";localPath:" +hdfsPath);
				//int returnCode = executor.execute("deploy task",System.out, System.err, taskDeploy, hdfsPath,localPath);
				try{
				    taskDeployer.deployTask(hdfsPath, localPath);
				    status.setStatus(DeploymentStatus.DEPLOY_SUCCESS);
                    s_logger.debug("Job " + taskID + " deploy successed");
				} catch(Exception e ){
				    s_logger.debug("Job " + taskID + " deploy failed");
                    status.setStatus(DeploymentStatus.DEPLOY_FAILED);
                    status.setFailureInfo(stdErr.toString());
				}
				conf.setLocalPath(localParentPath);
			} catch(Exception e){
				s_logger.error(e,e);
				status.setStatus(DeploymentStatus.DEPLOY_FAILED);
				status.setFailureInfo(stdErr.toString());
			}
		}
	}

	private static final class UndeploymentThread implements Runnable{
		
		String localIp;
		DeploymentInfoChannel cs;
		String taskID;
		
		UndeploymentThread( String localIp, DeploymentInfoChannel cs, String taskID){
			this.localIp = localIp;
			this.cs = cs;
			this.taskID = taskID;
		}

		@Override
		public void run() {
			Lock lock = getLock(taskID);
			try{
				lock.lock();
				DeploymentConf conf = (DeploymentConf) cs.getConf(localIp, taskID);
				DeploymentStatus status = (DeploymentStatus) cs.getStatus(localIp, taskID, null);
				if(status == null || status.getStatus() == DeploymentStatus.DELETE_SUCCESS){
					return;
				}

				undeployTask(conf, status);
				cs.updateStatus(localIp, taskID, status);
			} finally{
				lock.unlock();
			}
		}
		
		private void undeployTask(DeploymentConf conf, DeploymentStatus status){
			if(conf == null){
				s_logger.error("DeploymentConf is empty!");
				return;
			}

			String localPath = conf.getLocalPath();
			if(localPath == null){
				s_logger.error("local path is empty!");
				return;
			}
			try{
			    if(new File(localPath).exists()) {
                    FileUtils.deleteDirectory(new File(localPath));
                    status.setStatus(DeploymentStatus.DELETE_SUCCESS);
                }
			} catch(Exception e){
				s_logger.error(e.getMessage(),e);
				status.setStatus(DeploymentStatus.DELETE_FAILED);
				status.setFailureInfo("delete failed");
			}
		}
		
	}
	
	private static abstract class BaseTaskWatcher implements Watcher{

		protected String localIp;
		protected DeploymentInfoChannel cs;

		private BaseTaskWatcher( String localIp, DeploymentInfoChannel cs){
			this.localIp = localIp;
			this.cs = cs;
		}
	}

	private static final class TaskDeployWatcher extends BaseTaskWatcher{

		private TaskDeployWatcher( String localIp, DeploymentInfoChannel cs){
			super(localIp, cs);
		}

		@Override
		public void process(WatchedEvent event) {
		    if(event.getType() == EventType.NodeChildrenChanged ) {
		        checkAndDeployTasks( localIp, cs, true);
		    }
		}
	}

	private static final class TaskUndeployWatcher extends BaseTaskWatcher{

		private TaskUndeployWatcher( String localIp, DeploymentInfoChannel cs){
			super( localIp, cs);
		}

		@Override
		public void process(WatchedEvent event) {
		    if(event.getType() == EventType.NodeChildrenChanged ) {
		        checkAndUndeployTasks( localIp, cs, true);
		    }
		}
	}


}
