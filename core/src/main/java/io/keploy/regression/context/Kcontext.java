package io.keploy.regression.context;

import io.keploy.grpc.stubs.Service;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for Keploy context which provides all the configuration required by keploy
 */
@Getter
@Setter
@NoArgsConstructor
public class Kcontext {

    private HttpServletRequest Request;

    private io.keploy.regression.Mode.ModeType Mode = io.keploy.regression.Mode.ModeType.MODE_RECORD;

    private Boolean FileExport = false;

    private String TestId;

    private List<Service.Dependency> Deps = new ArrayList<>();

    private List<Service.Mock> Mock = new ArrayList<>();

    public Kcontext(HttpServletRequest request, io.keploy.regression.Mode.ModeType mode, Boolean fileExport, String testId, List<Service.Dependency> deps, List<Service.Mock> mock) {
        Request = request;
        Mode = mode;
        FileExport = fileExport;
        TestId = testId;
        Deps = deps;
        Mock = mock;
    }
}