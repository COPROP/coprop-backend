package bo.coprop;

import org.springframework.boot.SpringApplication;

public class TestCopropBackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(CopropBackendApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
