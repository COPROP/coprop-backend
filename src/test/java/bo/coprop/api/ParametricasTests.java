package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.Alcance;
import bo.coprop.identity.MatrizDePermisos;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.Permiso;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * El catalogo de parametricas y la matriz expuesta. Issue #89.
 *
 * <p>Los dos endpoints son triviales de escribir, y lo que de verdad hay que sostener es que
 * <strong>no se queden atras</strong>. El valor del ticket es que el cliente no copie a mano lo que
 * el backend ya sabe, y eso se pierde en silencio el dia que alguien añada una enumeracion o cambie
 * una celda de la matriz y aqui no salga. De ahi que dos de los cinco casos no miren la respuesta
 * sino la distancia entre la respuesta y el codigo.
 */
@Import({TestcontainersConfiguration.class, ProveedorDeTokensDePrueba.class})
@SpringBootTest
@AutoConfigureMockMvc
class ParametricasTests {

    private static final String PARAMETRICAS = "/api/v1/parametricas";
    private static final String MATRIZ = PARAMETRICAS + "/matriz-de-permisos";

    /** Los paquetes raiz de los tres modulos cuyas enumeraciones son contrato. */
    private static final List<String> PAQUETES =
            List.of("bo.coprop.condominium", "bo.coprop.identity", "bo.coprop.shared");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People personas;

    @Test
    @DisplayName("El catalogo trae cada enumeracion con sus valores en el orden en que se declararon")
    void elCatalogoTraeCadaEnumeracionConSusValores() throws Exception {
        mockMvc.perform(get(PARAMETRICAS).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enumeraciones.CondominiumType.length()").value(3))
                .andExpect(jsonPath("$.enumeraciones.CondominiumType[0]").value("BUILDING"))
                .andExpect(
                        jsonPath("$.enumeraciones.CondominiumStatus.length()").value(2))
                .andExpect(jsonPath("$.enumeraciones.UnitType.length()").value(5))
                .andExpect(jsonPath("$.enumeraciones.AccountStatus.length()").value(4))
                // El orden de los valores es el de la declaracion y no el alfabetico: un
                // desplegable que ofrece INVITED antes que ACTIVE sigue el ciclo de vida de la
                // cuenta, y ordenarlo alfabeticamente lo romperia.
                .andExpect(jsonPath("$.enumeraciones.AccountStatus[0]").value("INVITED"))
                .andExpect(jsonPath("$.enumeraciones.MembershipRole.length()").value(7))
                .andExpect(jsonPath("$.enumeraciones.MembershipRole[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.enumeraciones.Permiso.length()").value(23))
                .andExpect(jsonPath("$.enumeraciones.CodigoDeError.length()").value(10));
    }

    @Test
    @DisplayName("El catalogo es exactamente las enumeraciones publicas del dominio, ni una mas ni una menos")
    void elCatalogoEsExactamenteLasEnumeracionesDelDominio() throws Exception {
        // Por igualdad y no por "cada una esta": si el escaner dejara de encontrar clases --por un
        // paquete renombrado, por ejemplo-- un contains pasaria sin comprobar nada, y este test es
        // toda la vigilancia que tiene el catalogo. La igualdad falla por los dos lados: una
        // enumeracion nueva que nadie expuso, y una expuesta que ya no es del contrato.
        String cuerpo = mockMvc.perform(get(PARAMETRICAS).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var expuestas = new ArrayList<String>();
        new ObjectMapper().readTree(cuerpo).get("enumeraciones").propertyNames().forEach(expuestas::add);

        assertThat(expuestas).containsExactlyInAnyOrderElementsOf(enumeracionesPublicasDelDominio());
    }

    @Test
    @DisplayName("La matriz declara los siete roles en las veintitres filas, y cada celda es la del codigo")
    void laMatrizDeclaraCadaCeldaComoLaDelCodigo() throws Exception {
        var respuesta = mockMvc.perform(get(MATRIZ).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(MembershipRole.values().length))
                .andExpect(jsonPath("$.permisos.length()").value(Permiso.values().length));

        Permiso[] permisos = Permiso.values();
        for (int fila = 0; fila < permisos.length; fila++) {
            Permiso permiso = permisos[fila];
            String ruta = "$.permisos[" + fila + "]";
            respuesta
                    .andExpect(jsonPath(ruta + ".permiso").value(permiso.name()))
                    .andExpect(jsonPath(ruta + ".pideSegundoFactor").value(MatrizDePermisos.pideSegundoFactor(permiso)))
                    .andExpect(jsonPath(ruta + ".alcances.length()").value(MembershipRole.values().length));
            for (MembershipRole rol : MembershipRole.values()) {
                Alcance esperado = MatrizDePermisos.alcanceDe(rol, permiso);
                respuesta.andExpect(jsonPath(ruta + ".alcances." + rol.name()).value(esperado.name()));
            }
        }
    }

    @Test
    @DisplayName("Las celdas que nadie tiene se declaran como NONE, no se omiten")
    void lasCeldasVaciasSeDeclaran() throws Exception {
        mockMvc.perform(get(MATRIZ).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                // CREATE_CONDOMINIUMS es la primera fila y solo la tiene el superadministrador: los
                // otros seis roles tienen que aparecer igual, con NONE.
                .andExpect(jsonPath("$.permisos[0].permiso").value("CREATE_CONDOMINIUMS"))
                .andExpect(jsonPath("$.permisos[0].alcances.PLATFORM_ADMIN").value("ALL"))
                .andExpect(jsonPath("$.permisos[0].alcances.CONDO_ADMIN").value("NONE"))
                .andExpect(jsonPath("$.permisos[0].alcances.BOARD").value("NONE"));
    }

    @Test
    @DisplayName("Sin un token valido, los dos endpoints devuelven 401")
    void sinTokenValidoDevuelve401() throws Exception {
        mockMvc.perform(get(PARAMETRICAS).header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(MATRIZ).header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Las enumeraciones publicas de los paquetes raiz de los tres modulos, por nombre simple.
     *
     * <p>Se excluyen los subpaquetes {@code internal}: no son contrato, y exponerlas ataria la API a
     * como este guardado el dato. Si algun dia aparece una enumeracion ahi, este test no la exige y
     * la decision de exponerla queda donde debe, que es en el controlador.
     */
    private static List<String> enumeracionesPublicasDelDominio() {
        var escaner = new ClassPathScanningCandidateComponentProvider(false);
        escaner.addIncludeFilter(new AssignableTypeFilter(Enum.class));
        var nombres = new ArrayList<String>();
        for (String paquete : PAQUETES) {
            escaner.findCandidateComponents(paquete).stream()
                    .map(definicion -> definicion.getBeanClassName())
                    .filter(Objects::nonNull)
                    .filter(nombre -> !nombre.contains(".internal."))
                    // Las clases anidadas que genera un valor de enum con cuerpo propio no son
                    // enumeraciones del contrato: son un detalle del compilador.
                    .filter(nombre -> !nombre.contains("$"))
                    .map(nombre -> nombre.substring(nombre.lastIndexOf(".") + 1))
                    .forEach(nombres::add);
        }
        return nombres;
    }

    private String token() {
        var persona = personas.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView cuenta = personas.openAccount(persona.id(), ProveedorDeTokensDePrueba.correoNuevo(), null, "admin");
        return "Bearer " + ProveedorDeTokensDePrueba.valido(ProveedorDeTokensDePrueba.sujetoNuevo(), cuenta.email());
    }
}
