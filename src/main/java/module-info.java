open module unreal.archive.umodrepack {
	requires java.base;
	requires java.net.http;
	requires java.desktop;

	requires shrimpworks.unreal.packages;
	requires unreal.archive.common;

	requires org.slf4j;
	requires org.slf4j.simple;

	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;

	requires xnio.api;
	requires undertow.core;
}
