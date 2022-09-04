package io.keploy.regression.context;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.mode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class Kcontext {

    private HttpServletRequest Request;

    private mode.ModeType Mode;

    private String TestId;

    private List<Service.Dependency> Deps;

    public Kcontext(HttpServletRequest request, io.keploy.regression.mode.ModeType mode, String testId, List<Service.Dependency> deps) {
        this.Request = request;
        this.Mode = mode;
        this.TestId = testId;
        this.Deps = deps;
    }
}