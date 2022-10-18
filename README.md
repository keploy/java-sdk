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
      <version>N.N.N</version> (eg: 1.0.13)
    </dependency>

or to *build.gradle*:

    implementation 'io.keploy:keploy-sdk:N.N.N' (eg: 1.0.13)

## Usage

- Start keploy server [refer](https://github.com/keploy/keploy#start-keploy-server)

- **Replace** `@SpringBootApplication` with `@SpringBootApplication(scanBasePackages = {"<your base package>", "io.keploy.servlet"}).` in your main class.


- **Configure Environment Variables**
    - `APP_NAME`           (default APP_NAME = myApp)
    - `APP_PORT`           (default APP_PORT = 8080)
    - `KEPLOY_URL`         (default KEPLOY_URL =  ̰http://localhost:6789/api)
    - `KEPLOY_MODE`        (default KEPLOY_MODE = record/test)
    - `KTESTS_PATH`        (default test/e2e directory of your application)
    - `DENOISE`            (default DENOISE = false)
      **Note:** By enabling denoise, it will filter out noisy fields for that testcases.


- **Generate testcases**
    - To generate/capture TestCases set  and run your application.
        1. Set `KEPLOY_MODE = record`
        2. Run your application.
        3. Make some API calls.

- **Run the testcases**
    - **Note:** Before running tests stop the sample application.

      - Set `KEPLOY_MODE = test` (default "record")
          - Using IDE
              1. Run your application.
              2. You can also run the application with coverage to see the test coverage.

          - Using command line
              1. Add below code in your testfile and run `mvn test`.

                 ```java
                    @Test
                    public void TestKeploy() throws InterruptedException {

                       CountDownLatch countDownLatch = HaltThread.getInstance().getCountDownLatch();
                       mode.setTestMode();

                       new Thread(() -> {
                           SamplesJavaApplication.main(new String[]{""});
                           countDownLatch.countDown();
                       }).start();

                       countDownLatch.await();
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
              - 4. Add Jacoco plugin to your *pom.xml*.
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

