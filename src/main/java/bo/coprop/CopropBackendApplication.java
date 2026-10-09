package bo.coprop;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CopropBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CopropBackendApplication.class, args);
    }

    /**
     * El reloj de la aplicacion, inyectable.
     *
     * <p>Nadie llama a {@code Instant.now()} directamente: con el reloj inyectado, un test puede
     * fijar la hora y comprobar vigencias y vencimientos sin esperar ni dormir. En un sistema que
     * calcula mora por dias de atraso, poder mentirle al reloj no es comodidad, es la unica forma
     * de probarlo.
     *
     * <p>En UTC a proposito. El analisis 12.2 fija que las fechas se guardan en UTC y que los
     * calculos de vencimiento y mora se hacen en la zona del condominio, que cada uno trae en su
     * propio campo.
     *
     * <p>Vive aqui, en la clase de arranque, y no en un modulo: no pertenece a ningun dominio y
     * ponerlo en uno obligaria a los demas a depender de el.
     */
    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }
}
