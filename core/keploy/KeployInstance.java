import keploy.Keploy;


public class KeployInstance {
    private static Keploy keploy;

    private static KeployInstance keployInstance;

    private KeployInstance() {
    }

    public static KeployInstance getInstance() {
        if (keployInstance == null) {
            synchronized (KeployInstance.class) {  //thread safe.
                if (keployInstance == null) {
                    keployInstance = new KeployInstance();
                }
            }
        }
        return keployInstance;
    }

    public static void setKeploy(Keploy keploy) {
        if (KeployInstance.keploy == null) {
            KeployInstance.keploy = keploy;
        }
    }

    public static Keploy getKeploy() {
        return keploy;
    }
}