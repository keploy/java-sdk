[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen?logo=github)](CODE_OF_CONDUCT.md)
[![Slack](.github/slack.svg)](https://join.slack.com/t/keploy/shared_invite/zt-12rfbvc01-o54cOG0X1G6eVJTuI_orSA)
[![License](.github/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/io.keploy/keploy-sdk.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.keploy%22%20AND%20a:%22keploy-sdk%22)

# Keploy

[Keploy](https://keploy.io) is a no-code testing platform that generates tests from API calls.

This is the client SDK for Keploy API testing platform. There are 2 modes:
1. **Record mode**
    1. Record requests, response and all external calls and sends to Keploy server.
    2. It runs the request on the API again to identify noisy fields.
    3. Sends the noisy fields to the keploy server to be saved along with the testcase.
2. **Test mode**
    1. Fetches testcases for the app from keploy server.
    2. Calls the API with same request payload in testcase.
    3. Validates the respones and uploads results to the keploy server


The Keploy Java SDK helps you to integrate keploy with java applications. (For other languages,
see [KEPLOY SDKs](https://docs.keploy.io/application-development))

## Contents

1. [Requirements](#requirements)
2. [Build configuration](#build-configuration)
3. [Usage](#usage)
4. [Community support](#community-support)
5. [Documentation(WIP)](#documentationwip)

## Requirements

- Java 1.8+

## Build configuration

[Find the latest release](https://search.maven.org/artifact/io.keploy/keploy-sdk) of the Keploy Java SDK at maven
central.

Add *keploy-sdk* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.keploy</groupId>
      <artifactId>keploy-sdk</artifactId>
      <version>N.N.N</version> (eg: 1.2.5)
    </dependency>

or to *build.gradle*:

    implementation 'io.keploy:keploy-sdk:N.N.N' (eg: 1.2.5)

## Usage

- **Start keploy server [refer](https://github.com/keploy/keploy#start-keploy-server)**

- **For Spring based application**
    - Add `@Import(KeployMiddleware.class)` below `@SpringBootApplication`  in your main class.
- **For Java EE application**
    - Specify the below filter above all other filters and servlets in the **web.xml** file.
      ```xml
        <filter>
            <filter-name>middleware</filter-name>
            <filter-class>io.keploy.servlet.KeployMiddleware</filter-class>
        </filter>
        <filter-mapping>
            <filter-name>middleware</filter-name>
            <url-pattern>/*</url-pattern>
        </filter-mapping>
      ```

- **Run along with agent to mock external calls of your API 🤩🔥**

    - Download the latest - Download the latest agent jar
      from [here](https://search.maven.org/artifact/io.keploy/keploy-sdk/1.2.5/jar)  (eg: 1.2.5)

    - Prefix `-javaagent:` with absolute classpath of agent jar (eg: `-javaagent:<your full path to agent jar>/agent-1.2.5.jar`) is possible through 3 ways:-

        1. **Using Intellij :** Go to Edit Configuration-> add VM options -> paste _java agent_ edited above.

        2. **Using Command Line :** 
            ```
            export JAVA_OPTS="$JAVA_OPTS -javaagent:<your full path to agent jar>/agent-1.2.5.jar"
            ```

        3. **Running via Tomcat Server :** 
            ```
            export CATALINA_OPTS="$CATALINA_OPTS -javaagent:<your full path to agent jar>/agent-1.2.5.jar"
            ```

- **Configure Environment Variables**
    - `APP_NAME`           (default APP_NAME = myApp)
    - `APP_PORT`           (default APP_PORT = 8080)
    - `DELAY`              (default DELAY = 5)(It is the estimate application startup time (in sec))
    - `KEPLOY_URL`         (default KEPLOY_URL = http://localhost:6789/api)
    - `KEPLOY_MODE`        (default KEPLOY_MODE = off)
    - `KEPLOY_TEST_PATH`         (default **/src/test/e2e/keploy-tests** directory of your application)
    - `KEPLOY_MOCK_PATH`         (default **/src/test/e2e/mocks** directory of your application)
    - `KEPLOY_ASSET_PATH`        (default **/src/test/e2e/assets** directory of your application)
    - `DENOISE`            (default DENOISE = false)
      **Note:** By enabling denoise, it will filter out noisy fields for the testcase.
    - `RUN_TEST_BEFORE_RECORD` (default RUN_TEST_BEFORE_RECORD = false)
      **Note:** It is used to maintain the same database state.


- **Generate testcases**
    - To generate/capture TestCases set  and run your application.
        1. Set `KEPLOY_MODE = record` (default "off")
        2. Run your application.
        3. Make some API calls.

- **Run the testcases**
    - **Note:** Before running tests stop the sample application.

        - Set `KEPLOY_MODE = test` (default "off")
            - Using IDE
                1. Run your application.
                2. You can also run the application with coverage to see the test coverage.

            - Using command line
                1. Add below code in your testfile and run `mvn test`.

                   ```java
                      @Test
                      public void TestKeploy() throws InterruptedException {

                          CountDownLatch countDownLatch = HaltThread.getInstance().getCountDownLatch();
                          Mode.setTestMode();

                          new Thread(() -> {
                              <Your Application Class>.main(new String[]{""});
                              countDownLatch.countDown();
                          }).start();

                          countDownLatch.await();
                          assertTrue(AssertKTests.result(), "Keploy Test Result");
                      }
                   ```     

                2. To get test coverage, in addition to above follow below instructions.

                3. Add maven-surefire-plugin to your *pom.xml*.

                   ```xml 
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>2.22.2</version>
                            <configuration>
  
                        <!-- <skipTests>true</skipTests> -->
  
                                <systemPropertyVariables>
                                    <jacoco-agent.destfile>target/jacoco.exec
                                    </jacoco-agent.destfile>
                                </systemPropertyVariables>
                            </configuration>
                        </plugin>
                   ```  
                4. Add Jacoco plugin to your *pom.xml*.
                      ```xml
                           <plugin>
                              <groupId>org.jacoco</groupId>
                              <artifactId>jacoco-maven-plugin</artifactId>
                              <version>0.8.5</version>
                              <executions>
                                  <execution>
                                      <id>prepare-agent</id>
                                      <goals>
                                          <goal>prepare-agent</goal>
                                      </goals>
                                  </execution>
                                  <execution>
                                      <id>report</id>
                                      <phase>prepare-package</phase>
                                      <goals>
                                          <goal>report</goal>
                                      </goals>
                                  </execution>
                                  <execution>
                                      <id>post-unit-test</id>
                                      <phase>test</phase>
                                      <goals>
                                          <goal>report</goal>
                                      </goals>
                                      <configuration>
                                          <!-- Sets the path to the file which contains the execution data. -->
    
                                          <dataFile>target/jacoco.exec</dataFile>
                                          <!-- Sets the output directory for the code coverage report. -->
                                          <outputDirectory>target/my-reports</outputDirectory>
                                      </configuration>
                                  </execution>
                              </executions>
                          </plugin>
                      ```
                5. Run your tests using command : `mvn test`.


### KEPLOY_MODE
There are 3 modes:
- **Record**: Sets to record mode.
- **Test**: Sets to test mode.
- **Off**: Turns off all the functionality provided by the API

**Note:** `KEPLOY_MODE` value is case sensitive.


## Community support

We'd love to collaborate with you to make Keploy great. To get started:

* [Slack](https://join.slack.com/t/keploy/shared_invite/zt-12rfbvc01-o54cOG0X1G6eVJTuI_orSA) - Discussions with the
  community and the team.
* [GitHub](https://github.com/keploy/java-sdk/issues) - For bug reports and feature requests.
