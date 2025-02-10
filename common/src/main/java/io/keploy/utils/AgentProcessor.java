package io.keploy.utils;


import io.keploy.utils.models.Http;
import io.keploy.utils.models.TestData;

public class AgentProcessor {

    public static void processResponse(io.keploy.utils.RequestWrapper wrappedRequest, io.keploy.utils.ResponseWrapper responseWrapper) {
        try {
            responseWrapper.flushBuffer();
            System.out.println("[Keploy Agent] Keploy has Captured a new TestCase");
            responseWrapper.writeResponseToClient();
            String capturedRequestBody = new String(wrappedRequest.getCachedBody(), java.nio.charset.StandardCharsets.UTF_8);
            String capturedResponseBody = responseWrapper.getCapturedResponseBody();
            java.util.Map<String, String> requestHeaders = wrappedRequest.getHeaderMap();
            java.util.Map<String, String> responseHeaders = responseWrapper.getHeaderMap();

            Http.HTTPReq httpRequest = new Http.HTTPReq();
            httpRequest.method = wrappedRequest.getMethod();
            httpRequest.proto_major = wrappedRequest.getProtocolMajor();
            httpRequest.proto_minor = wrappedRequest.getProtocolMinor();
            httpRequest.url = wrappedRequest.getRequestURL().toString();
            httpRequest.header = requestHeaders;
            httpRequest.body = capturedRequestBody;
            Http.HTTPResp httpResponse = new Http.HTTPResp();
            httpResponse.status_code = responseWrapper.getStatus();
            httpResponse.header = responseHeaders;
            httpResponse.body = capturedResponseBody;

            TestData testData = new TestData(httpRequest, httpResponse);
            ApiClient apiClient = new ApiClient();
            apiClient.sendTestDataToAPI(testData);
        } catch (Exception e) {
            System.err.println("[AgentProcessor] Error processing request/response: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
