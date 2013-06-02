package com.dp.bigdata.taurus.restlet.resource.impl;

import org.restlet.data.Form;
import org.restlet.data.Parameter;
import org.restlet.data.Status;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import org.springframework.beans.factory.annotation.Autowired;

import com.dp.bigdata.taurus.core.ScheduleException;
import com.dp.bigdata.taurus.core.Scheduler;
import com.dp.bigdata.taurus.restlet.resource.INameResource;
import com.mysql.jdbc.StringUtils;

/**
 * Resource url : http://xxx.xxx/api/name?task_name={xxx}
 * 
 * @author damon.zhu
 */
public class NameResource extends ServerResource implements INameResource {

    private static final String TASK = "task_name";

    @Autowired
    private Scheduler scheduler;

    @Override
    @Get
    public boolean hasName() {
        Form form = getRequest().getResourceRef().getQueryAsForm();
        for (Parameter parameter : form) {
            if (parameter.getName().equals(TASK)) {
                return isExistTaskName(parameter.getValue());
            }
        }
        setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
        return false;
    }

    public boolean isExistTaskName(String name) {
        if (StringUtils.isNullOrEmpty(name)) {
            return false;
        }
        try {
            scheduler.getTaskByName(name);
            return true;
        } catch (ScheduleException e) {
        	try {
				scheduler.getTaskByName(name + "#1");
				return true;
			} catch (ScheduleException e1) {
				return false;
			}
        }
    }
}
