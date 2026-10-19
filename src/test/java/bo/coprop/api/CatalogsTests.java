package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.Permission;
import bo.coprop.identity.PermissionMatrix;
import bo.coprop.identity.Scope;
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
@Import({TestcontainersConfiguration.class, TestTokenProvider.class})
@SpringBootTest
@AutoConfigureMockMvc
class CatalogsTests {

    private static final String PARAMETRICAS = "/api/v1/catalogs";
    private static final String MATRIZ = PARAMETRICAS + "/permission-matrix";

    /** Los paquetes raiz de los tres modulos cuyas enumeraciones son contrato. */
    private static final List<String> PAQUETES =
            List.of("bo.coprop.condominium", "bo.coprop.identity", "bo.coprop.shared");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private People people;

    @Test
    @DisplayName("El catalogo trae cada enumeracion con sus valores en el orden en que se declararon")
    void theCatalogBringsEachEnumerationWithItsValues() throws Exception {
        mockMvc.perform(get(PARAMETRICAS).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enumerations.CondominiumType.length()").value(3))
                .andExpect(jsonPath("$.enumerations.CondominiumType[0]").value("BUILDING"))
                .andExpect(jsonPath("$.enumerations.CondominiumStatus.length()").value(2))
                .andExpect(jsonPath("$.enumerations.UnitType.length()").value(5))
                .andExpect(jsonPath("$.enumerations.AccountStatus.length()").value(4))
                // El orden de los valores es el de la declaracion y no el alfabetico: un
                // desplegable que ofrece INVITED antes que ACTIVE sigue el ciclo de vida de la
                // cuenta, y ordenarlo alfabeticamente lo romperia.
                .andExpect(jsonPath("$.enumerations.AccountStatus[0]").value("INVITED"))
                .andExpect(jsonPath("$.enumerations.MembershipRole.length()").value(7))
                .andExpect(jsonPath("$.enumerations.MembershipRole[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.enumerations.Permission.length()").value(23))
                .andExpect(jsonPath("$.enumerations.ErrorCode.length()").value(10));
    }

    @Test
    @DisplayName("El catalogo es exactamente las enumeraciones publicas del dominio, ni una mas ni una menos")
    void theCatalogIsExactlyTheDomainEnumerations() throws Exception {
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
        new ObjectMapper().readTree(cuerpo).get("enumerations").propertyNames().forEach(expuestas::add);

        assertThat(expuestas).containsExactlyInAnyOrderElementsOf(enumeracionesPublicasDelDominio());
    }

    @Test
    @DisplayName("La matriz declara los siete roles en las veintitres filas, y cada celda es la del codigo")
    void theMatrixDeclaresEachCellAsTheCodeDoes() throws Exception {
        var response = mockMvc.perform(get(MATRIZ).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(MembershipRole.values().length))
                .andExpect(jsonPath("$.permissions.length()").value(Permission.values().length));

        Permission[] permissions = Permission.values();
        for (int fila = 0; fila < permissions.length; fila++) {
            Permission permission = permissions[fila];
            String path = "$.permissions[" + fila + "]";
            response.andExpect(jsonPath(path + ".permission").value(permission.name()))
                    .andExpect(jsonPath(path + ".requiresSecondFactor")
                            .value(PermissionMatrix.requiresSecondFactor(permission)))
                    .andExpect(jsonPath(path + ".scopes.length()").value(MembershipRole.values().length));
            for (MembershipRole rol : MembershipRole.values()) {
                Scope expected = PermissionMatrix.scopeOf(rol, permission);
                response.andExpect(jsonPath(path + ".scopes." + rol.name()).value(expected.name()));
            }
        }
    }

    @Test
    @DisplayName("Las celdas que nadie tiene se declaran como NONE, no se omiten")
    void theEmptyCellsAreDeclared() throws Exception {
        mockMvc.perform(get(MATRIZ).header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                // CREATE_CONDOMINIUMS es la primera fila y solo la tiene el superadministrador: los
                // otros seis roles tienen que aparecer igual, con NONE.
                .andExpect(jsonPath("$.permissions[0].permission").value("CREATE_CONDOMINIUMS"))
                .andExpect(jsonPath("$.permissions[0].scopes.PLATFORM_ADMIN").value("ALL"))
                .andExpect(jsonPath("$.permissions[0].scopes.CONDO_ADMIN").value("NONE"))
                .andExpect(jsonPath("$.permissions[0].scopes.BOARD").value("NONE"));
    }

    @Test
    @DisplayName("Sin un token valido, los dos endpoints devuelven 401")
    void withoutAValidTokenItReturns401() throws Exception {
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
        var names = new ArrayList<String>();
        for (String paquete : PAQUETES) {
            escaner.findCandidateComponents(paquete).stream()
                    .map(definition -> definition.getBeanClassName())
                    .filter(Objects::nonNull)
                    .filter(name -> !name.contains(".internal."))
                    // Las clases anidadas que genera un valor de enum con cuerpo propio no son
                    // enumeraciones del contrato: son un detalle del compilador.
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(name.lastIndexOf(".") + 1))
                    .forEach(names::add);
        }
        return names;
    }

    private String token() {
        var person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        AccountView account = people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
        return "Bearer " + TestTokenProvider.valid(TestTokenProvider.sujetoNuevo(), account.email());
    }
}
