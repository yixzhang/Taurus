<%@ page contentType="text/html;charset=utf-8" %>
<div class="navbar navbar-inverse navbar-fixed-top">
	<div class="navbar-inner">
		<div class="container">
			<a class="brand" href="./index.jsp">Taurus</a>
			<div class="nav-collapse collapse">
				<p class="navbar-text pull-right">
                	<% 
						String currentUser = (String) session.getAttribute(com.dp.bigdata.taurus.web.servlet.LoginServlet.USER_NAME);
						if(currentUser != null){
					%>
                    	<a role="button" class="btn-link"><i class="icon-user icon-white"></i> <%=currentUser%></a>
                    <%}else{%>
						<a href="./signin.jsp" role="button" class="btn-link" data-toggle="modal"><i class="icon-user icon-white"></i> 登陆</a>
                    <%}%>
				</p>

				<ul class="nav">
					<li class=""><a href="index.jsp">Home</a>
					</li>
					<li class=""><a href="task.jsp">新建任务</a>
					</li>
					<li class=""><a href="batch-task.jsp">批量设置</a>
					</li>
					<li class=""><a href="schedule.jsp">调度中心</a>
					</li>
				</ul>
			</div>
		</div>
	</div>
</div>