package io.keploy.ksql;


public class KDeserialize implements Deserial{
    public interface face{};
    private String leg;

    public KDeserialize(){
        System.out.println("Fire ....");
    }
    public KDeserialize(int x){
        System.out.println("Water .... "+x);
    }
    private void method1(){
        System.out.println("Viole");
    }
    public int method2(){
        return  1234;
    }

    @Override
    public void method3() {
        System.out.println("Last method");
    }
}

