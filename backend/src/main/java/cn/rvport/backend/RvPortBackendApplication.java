package cn.rvport.backend;

import cn.rvport.backend.config.CredentialCryptoProperties;
import cn.rvport.backend.config.ObjectStorageProperties;
import cn.rvport.backend.config.RedisCacheProperties;
import cn.rvport.backend.config.RvPortAppMode;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.config.TaskCancelQueueProperties;
import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.config.TaskSseBroadcastProperties;
import cn.rvport.backend.config.UserActionTokenCleanupProperties;
import cn.rvport.backend.config.WorkerProperties;
import cn.rvport.backend.config.WorkerCleanupProperties;
import java.util.function.Function;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        RvPortProperties.class,
        cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties.class,
        CredentialCryptoProperties.class,
        RedisCacheProperties.class,
        TaskQueueProperties.class,
        TaskCancelQueueProperties.class,
        TaskSseBroadcastProperties.class,
        ObjectStorageProperties.class,
        UserActionTokenCleanupProperties.class,
        WorkerProperties.class,
        WorkerCleanupProperties.class
})
public class RvPortBackendApplication {

    static Function<String, SpringApplication> applicationFactory = RvPortBackendApplication::createApplication;

    public static void main(String[] args) {
        launch(args, System.getenv("RVPORT_APP_MODE"), applicationFactory);
    }

    static void launch(String[] args, String appMode, Function<String, SpringApplication> applicationFactory) {
        SpringApplication application = applicationFactory.apply(appMode);
        application.run(args);
    }

    static SpringApplication createApplication(String appMode) {
        SpringApplication application = new SpringApplication(RvPortBackendApplication.class);
        if (isWorkerMode(appMode)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        return application;
    }

    private static boolean isWorkerMode(String appMode) {
        RvPortAppMode mode = RvPortAppMode.from(appMode);
        return mode == RvPortAppMode.WORKER;
    }
}
