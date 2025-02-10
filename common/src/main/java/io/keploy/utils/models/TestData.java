package io.keploy.utils.models;


import java.util.UUID;

public class TestData {
    public String id;
    public Http.HTTPReq http_req;
    public Http.HTTPResp http_resp;

    public TestData(Http.HTTPReq httpRequest, Http.HTTPResp httpResponse) {
        this.id = UUID.randomUUID().toString(); // Generate a unique ID for each test
        this.http_req = httpRequest;
        this.http_resp = httpResponse;
    }
}
