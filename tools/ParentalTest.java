package ec.net.blue.tv;

/** Prueba la deteccion de categorias para adultos y el hash de la contrasena. */
public class ParentalTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "OK   " : "FALLA") + " " + n); if (!ok) fails++; }
    public static void main(String[] a) {
        String[] adult = {"ADULTOS", "Adultos", "adulto", "CANALES ADULTOS +18", "XXX", "Para adultos", "+18", "18+ Night", "ADÚLTOS", "Adult", "Eróticos"};
        String[] normal = {"Noticias", "Deportes", "Infantiles", "Películas 2018", "Series HD", "Ecuador", "Música", "Documentales", "Hot Topics", "Cocina"};
        for (String s : adult) check("bloquea '" + s + "'", Parental.isAdult(s));
        for (String s : normal) check("no bloquea '" + s + "'", !Parental.isAdult(s));
        check("null no bloquea", !Parental.isAdult(null));
        String h = Parental.hash("abcd", "1234");
        check("hash determinista", h.equals(Parental.hash("abcd", "1234")) && h.length() == 64);
        check("otra clave -> otro hash", !h.equals(Parental.hash("abcd", "1235")));
        check("otra sal -> otro hash", !h.equals(Parental.hash("abce", "1234")));
        System.out.println(fails == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLAS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
