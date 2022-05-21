package io.keploy.agent.Dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ClassHolder {

    Class<?> targetCls;
    ClassLoader targetClassLoader;
}
