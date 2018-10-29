package com.example;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public class DaggerApplication implements Function<String, String> {

	public static void main(String[] args) {
		DaggerMain.builder()
				.functionEndpointFactory(
						new FunctionEndpointFactory(new DaggerApplication()))
				.environmentFactory(new EnvironmentFactory(args)).build().server().run();
	}

	@Override
	public String apply(String t) {
		return t.toUpperCase();
	}
}

@Component(modules = FunctionEndpointFactory.class)
@Singleton
interface Main {
	@Named("server")
	Runnable server();

	@Named("local-server-port")
	int port();
}

@Module(includes = NettyServerFactory.class)
class FunctionEndpointFactory {

	private Function<String, String> function;

	public FunctionEndpointFactory(Function<String, String> function) {
		this.function = function;
	}

	@Provides
	@Singleton
	public RouterFunction<?> functionEndpoints() {
		return route(POST("/"), request -> ok()
				.body(request.bodyToMono(String.class).map(function), String.class));
	}

}

@Module(includes = EnvironmentFactory.class)
class NettyServerFactory {

	private static Log logger = LogFactory.getLog(NettyServerFactory.class);
	private volatile int port;
	private CountDownLatch latch = new CountDownLatch(1);

	@Provides
	@Singleton
	@Named("server")
	public Runnable handler(ConfigurableEnvironment environment,
			HttpWebHandlerAdapter handler) {
		return () -> {
			Integer port = Integer.valueOf(
					environment.resolvePlaceholders("${server.port:${PORT:8080}}"));
			String address = environment.resolvePlaceholders("${server.address:0.0.0.0}");
			if (port >= 0) {
				ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(
						handler);
				HttpServer httpServer = HttpServer.create().host(address).port(port)
						.handle(adapter);
				Thread thread = new Thread(() -> httpServer
						.bindUntilJavaShutdown(Duration.ofSeconds(60), this::callback),
						"server-startup");
				thread.setDaemon(false);
				thread.start();
			}
			else {
				logger.info("No server to run (port=" + port + ")");
				this.port = port;
				this.latch.countDown();
			}
		};
	}

	@Provides
	@Singleton
	@Named("local-server-port")
	int port() {
		try {
			latch.await(100, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return this.port;
	}

	@Provides
	@Singleton
	public HttpWebHandlerAdapter httpHandler(RouterFunction<?> router) {
		return (HttpWebHandlerAdapter) RouterFunctions.toHttpHandler(router,
				HandlerStrategies.empty().codecs(config -> config.registerDefaults(true))
						.build());
	}

	private void callback(DisposableServer server) {
		this.port = server.port();
		this.latch.countDown();
		logger.info("Server started on port=" + server.port());
		try {
			double uptime = ManagementFactory.getRuntimeMXBean().getUptime();
			logger.info("JVM running for " + uptime + "ms");
		}
		catch (Throwable e) {
			// ignore
		}
	}
}

@Module
class EnvironmentFactory {

	private SimpleCommandLinePropertySource properties;

	public EnvironmentFactory(String... args) {
		if (args != null && args.length > 0) {
			properties = new SimpleCommandLinePropertySource(args);
		}
	}

	@Provides
	@Singleton
	public ConfigurableEnvironment environment() {
		StandardEnvironment environment = new StandardEnvironment();
		if (properties != null) {
			environment.getPropertySources().addFirst(properties);
		}
		return environment;
	}
}