package com.dp.bigdata.taurus.restlet.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.restlet.Request;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.springframework.beans.factory.annotation.Autowired;

import com.dp.bigdata.taurus.core.CronExpression;
import com.dp.bigdata.taurus.core.IDFactory;
import com.dp.bigdata.taurus.core.TaskStatus;
import com.dp.bigdata.taurus.core.parser.DependencyParser;
import com.dp.bigdata.taurus.generated.module.Task;
import com.dp.bigdata.taurus.restlet.exception.DuplicatedNameException;
import com.dp.bigdata.taurus.restlet.exception.InvalidArgumentException;
import com.dp.bigdata.taurus.restlet.resource.impl.NameResource;
import com.dp.bigdata.taurus.restlet.shared.GWTTaskDetailControlName;

/**
 * TaskRequestExtractor
 * 
 * @author damon.zhu
 */
public class TaskRequestExtractor implements RequestExtrator<Task>{
    
    private static final Log LOG = LogFactory.getLog(TaskRequestExtractor.class);

    @Autowired
    private IDFactory idFactory;
    
    @Autowired
    private PoolManager poolManager;
    
    @Autowired
    private FilePathManager filePathManager;
    
    @Autowired
    private NameResource nameResource;
    
    public Task extractTask(Request request, boolean isUpdateAction) throws Exception {
        List<FileItem> items = getFileItem(request);
        Task task = new Task();
        Date current = new Date();
        if (!isUpdateAction) {
            task.setAddtime(current);
            task.setLastscheduletime(current);
            String id = idFactory.newTaskID();
            task.setTaskid(id);
            task.setStatus(TaskStatus.RUNNING);
        }
        task.setUpdatetime(current);
        String name = CookieUtils.getUser(request);
        task.setCreator(name);
        for (final Iterator<FileItem> it = items.iterator(); it.hasNext();) {
            FileItem fi = it.next();
            if (fi.isFormField()) {
                String key = fi.getFieldName();
                String value = fi.getString();
                if (key.equals(GWTTaskDetailControlName.TASKNAME.getName())) {
                    task.setName(value);
                } else if (key.equals(GWTTaskDetailControlName.TASKTYPE.getName())) {
                    task.setType(value);
                } else if (key.equals(GWTTaskDetailControlName.TASKPOOL.getName())) {
                    int pid = poolManager.getID(value);
                    task.setPoolid(pid);
                } else if (key.equals(GWTTaskDetailControlName.TASKCOMMAND.getName())) {
                    task.setCommand(value);
                } else if (key.equals(GWTTaskDetailControlName.MULTIINSTANCE.getName())) {
                    task.setAllowmultiinstances(Integer.parseInt(value));
                } else if (key.equals(GWTTaskDetailControlName.CRONTAB.getName())) {
                    task.setCrontab(value);
                } else if (key.equals(GWTTaskDetailControlName.DEPENDENCY.getName())) {
                    task.setDependencyexpr(value);
                } else if (key.equals(GWTTaskDetailControlName.PROXYUSER.getName())) {
                    task.setProxyuser(value);
                } else if (key.equals(GWTTaskDetailControlName.MAXEXECUTIONTIME.getName())) {
                    task.setExecutiontimeout(Integer.parseInt(value));
                } else if (key.equals(GWTTaskDetailControlName.MAXWAITTIME.getName())) {
                    task.setWaittimeout(Integer.parseInt(value));
                } else if (key.equals(GWTTaskDetailControlName.RETRYTIMES.getName())) {
                    int retryNum = Integer.parseInt(value);
                    task.setRetrytimes(retryNum);
                    if(retryNum > 0){
                        task.setIsautoretry(true); 
                    }else{
                        task.setIsautoretry(false);
                    }
                }
            } else {
                if (StringUtils.isNotEmpty(fi.getName()) && StringUtils.isNotBlank(fi.getName())) {
                    String filePath = filePathManager.getLocalPath(fi.getName());
                    task.setFilename(fi.getName());
                    File file = new File(filePath);
                    fi.write(file);
                } else {
                    LOG.error("Upload file failed.");
                    throw new FileNotFoundException("Task file not found!");
                }
            }
        }
        validate(task);
        return task;
    }
    
    private List<FileItem> getFileItem(Request request) throws FileUploadException {
        DiskFileItemFactory factory = new DiskFileItemFactory();
        factory.setSizeThreshold(1000240);
        RestletFileUpload upload = new RestletFileUpload(factory);
        List<FileItem> items = upload.parseRequest(request);
        return items;
    }

    private void validate(Task task) throws Exception {
        if(task.getDependencyexpr() != null && task.getDependencyexpr() != ""){
            if(!DependencyParser.isValidateExpression(task.getDependencyexpr())){
                throw new InvalidArgumentException("Invalid dependency expression : " + task.getDependencyexpr());
            }
        }
        
        if (nameResource.isExistTaskName(task.getName())) {
            throw new DuplicatedNameException("Duplicated Name : " + task.getName());
        }

        if (!CronExpression.isValidExpression(task.getCrontab())) {
            throw new InvalidArgumentException("Invalid crontab expression : " + task.getCrontab());
        }
    }

}
