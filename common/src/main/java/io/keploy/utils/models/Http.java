package io.keploy.utils.models;

import java.util.Map;

public class Http {

    public static class HTTPReq {
        public String method;
        public int proto_major;
        public int proto_minor;
        public String url;
        public Map<String, String> url_params;
        public Map<String, String> header;
        public String body;
        public String binary;
        // Omitting Form and Timestamp as requested
    }

    public static class HTTPResp {
        public int status_code;
        public Map<String, String> header;
        public String body;
        public String status_message;
        public int proto_major;
        public int proto_minor;
        public String binary;
        // Omitting Timestamp as requested
    }
}
