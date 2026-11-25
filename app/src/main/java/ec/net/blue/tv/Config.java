package ec.net.blue.tv;

/** Configuracion fija de la app. El cliente nunca ve estos valores en pantalla. */
final class Config {
    /** Servidor Xtream de BLUEnet (host; la app prueba http/https y puertos comunes). */
    static final String SERVER = "catv.blue.net.ec";
    static final String TAG = "BLUEnetTV";

    private Config() {
    }
}
