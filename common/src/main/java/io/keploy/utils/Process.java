package io.keploy.utils;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class Process {

//    public static final Logger logger = LogManager.getLogger(Process.class);

    public static final String CROSS = new String(Character.toChars(0x274C));

    public static final String WARN = "\u26A0\uFE0F";

    public static void method(){
        System.out.println("AHJDHAJHDJFJhdf");
    }

    public static void method2() throws IOException {
        System.out.println("a;skfgpsgf;la");
        Helper.ExtractEnv("KEPLOY_MODE");
       APIKeyAuth.ApiKeyRes response =  APIKeyAuth.checkApiKeyAuth("https://api.keploy.io","","xRp5nyiQ+B6yltBUpw==",true);
        if (response.isValid()) {
            System.out.println("Authentication successful!");
            System.out.println("Company ID: " + response.getCompanyId());
            System.out.println("Email ID: " + response.getEmailId());
            System.out.println("JWT Token: " + response.getJwtToken());
        } else {
            System.out.println("Authentication failed: " + response.getError());
        }
    }


}
