package com.dp.bigdata.taurus.zookeeper.common.infochannel.guice;

import org.apache.zookeeper.ZooKeeper;

import com.dp.bigdata.taurus.zookeeper.common.infochannel.TaurusZKDeploymentInfoChannel;
import com.dp.bigdata.taurus.zookeeper.common.infochannel.interfaces.DeploymentInfoChannel;
import com.google.inject.AbstractModule;

public class DeploymentInfoChannelModule extends AbstractModule{
	@Override
	protected void configure() {
		bind(DeploymentInfoChannel.class).to(TaurusZKDeploymentInfoChannel.class);
		bindZooKeeper();
		configureOthers();
	}
	
	protected void configureOthers(){
		
	}
	protected void bindZooKeeper() {
		bind(ZooKeeper.class).toProvider(ZooKeeperProvider.class);
	}
	
	
}
