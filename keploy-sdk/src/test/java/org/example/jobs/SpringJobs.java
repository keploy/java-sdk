package org.example.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SpringJobs is the app's Spring context: scheduling on, DigestJobs, and its scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SpringJobs {

    @Bean
    public DigestJobs digestJobs() {
        return new DigestJobs();
    }

    @Bean
    public OnDemandScheduler taskScheduler() {
        return new OnDemandScheduler();
    }
}
