package io.keploy.utils;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class depsobj<T> {

    private boolean mock;

    private T res;

    public depsobj(boolean mock, T res) {
        this.mock = mock;
        this.res = res;
    }
}
