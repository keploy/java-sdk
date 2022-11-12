package io.keploy.utils;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class depsobj<T> {

    private boolean mock;

    private List<T> res;

    public depsobj(boolean mock, List<T> res) {
        this.mock = mock;
        this.res = res;
    }
}