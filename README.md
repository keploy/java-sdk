[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen?logo=github)](CODE_OF_CONDUCT.md)
[![Slack](.github/slack.svg)](https://join.slack.com/t/keploy/shared_invite/zt-12rfbvc01-o54cOG0X1G6eVJTuI_orSA)
[![License](.github/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

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
      <version>N.N.N</version>
    </dependency>

or to *build.gradle*:

    compile group: 'io.keploy', name: 'keploy-sdk', version: 'N.N.N'

## Usage

- Replace `@SpringBootApplication` with `@SpringBootApplication(scanBasePackages = {"<your base package>", "io.keploy.servlet"}).` in your main class.


- Configure Environment Variables
    - `APP_NAME`           (eg: APP_NAME=demoApp)
    - `KEPLOY_MODE`        (eg: KEPLOY_MODE=record/test)
    - `KEPLOY_SERVER_PORT` (eg: KEPLOY_SERVER_PORT=8081)
    - `KEPLOY_CLIENT_PORT` (eg: KEPLOY_CLIENT_PORT=8080)

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
* [GitHub](https://github.com/keploy/keploy/issues) - For bug reports and feature requests.


## Documentation(WIP):

https://docs.google.com/document/d/1scq1sGUPeWyupBqtZCDnbx2G1GMtchHBdRql9XwEEtE/edit?usp=sharing


