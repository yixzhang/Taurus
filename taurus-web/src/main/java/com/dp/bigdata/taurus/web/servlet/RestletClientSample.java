package com.dp.bigdata.taurus.web.servlet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;

import org.restlet.data.MediaType;
import org.restlet.resource.ClientResource;

import com.dp.bigdata.taurus.restlet.resource.ITasksResource;
import com.dp.bigdata.taurus.restlet.shared.TaskDTO;

public class RestletClientSample {

    public static void main(String args[]){
        ClientResource cr = new ClientResource("http://192.168.26.87:8182/api/task");
        ITasksResource resource = cr.wrap(ITasksResource.class);
         cr.accept(MediaType.APPLICATION_XML);
        ArrayList<TaskDTO> tasks = resource.retrieve();
        
        SimpleDateFormat formatter =  new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for(TaskDTO dto : tasks){
            System.out.println(formatter.format(dto.getAddtime()));
        }
    }
}
