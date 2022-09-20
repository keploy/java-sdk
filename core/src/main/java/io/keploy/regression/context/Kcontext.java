package io.keploy.regression.context;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.mode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class Kcontext {

    private HttpServletRequest Request;

    private mode.ModeType Mode;

    private Boolean FileExport;

    private String TestId;

    private List<Service.Dependency> Deps = new ArrayList<>();

    private List<Service.Mock> Mock = new ArrayList<>();

    public Kcontext(HttpServletRequest request, mode.ModeType mode, Boolean fileExport, String testId, List<Service.Dependency> deps, List<Service.Mock> mock) {
        Request = request;
        Mode = mode;
        FileExport = fileExport;
        TestId = testId;
        Deps = deps;
        Mock = mock;
    }
}