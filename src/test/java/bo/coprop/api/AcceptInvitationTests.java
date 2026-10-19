package bo.coprop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bo.coprop.TestcontainersConfiguration;
import bo.coprop.condominium.CondominiumType;
import bo.coprop.condominium.Condominiums;
import bo.coprop.condominium.NewCondominium;
import bo.coprop.identity.AccountView;
import bo.coprop.identity.InvitationDoubles;
import bo.coprop.identity.Invitations;
import bo.coprop.identity.MembershipRole;
import bo.coprop.identity.MembershipView;
import bo.coprop.identity.Memberships;
import bo.coprop.identity.NewPerson;
import bo.coprop.identity.People;
import bo.coprop.identity.PersonView;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El segundo criterio del issue #18, con un token de verdad: <strong>aceptar una invitacion con una
 * cuenta del mismo correo vincula la cuenta existente</strong> en vez de crear una segunda.
 *
 * <p>Esta aqui y no en {@code InvitationsTests} porque hace falta el token firmado de
 * {@code TestTokenProvider}: lo que se comprueba es la costura entre dos issues --el primer
 * login del #13, que vincula por correo verificado, y la aceptacion del #18-- y con un usuario
 * simulado esa costura no existe.
 *
 * <p><strong>Lo que falta para cumplirlo al pie de la letra es Google.</strong> El criterio lo
 * nombra, y eso necesita credenciales de un proyecto real, que es el mismo cabo suelto que deja
 * abierto el issue #12. Lo que si se demuestra es el mecanismo entero: el proveedor dice quien es
 * con un correo verificado, COPROP cae en la cuenta que ya existia, y la invitacion solo agrega la
 * membresia. Para esta aplicacion, un token de Google valido y uno de contraseña valido son el mismo
 * token con otro emisor de identidades detras --los dos llegan como JWT del realm-- asi que el
 * camino que se recorre aqui es el que recorreria Google.
 */
@Import({TestcontainersConfiguration.class, TestTokenProvider.class, InvitationDoubles.class})
@SpringBootTest
@AutoConfigureMockMvc
class AcceptInvitationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Condominiums condominiums;

    @Autowired
    private People people;

    @Autowired
    private Memberships memberships;

    @Autowired
    private Invitations invitations;

    @Autowired
    private InvitationDoubles.CorreoDePrueba buzon;

    @Test
    @DisplayName("quien entra con el correo de la invitacion cae en su cuenta y no en una nueva")
    void aceptarVinculaLaCuentaQueYaExistia() throws Exception {
        UUID condominium = altaDeCondominio();

        // Quien invita: administradora con membresia, porque invitar exige poder conceder roles.
        AccountView administradora = cuentaNueva();
        memberships.grant(condominium, administradora.id(), Set.of(MembershipRole.CONDO_ADMIN), null, "admin");

        // La invitada: persona y cuenta ya dadas de alta por la administradora (el orden de 6.1), y
        // sin haber entrado nunca.
        AccountView invitada = cuentaNueva();
        UUID personaDeLaInvitada = invitada.personId();

        invitations.issue(
                condominium,
                invitada.id(),
                Set.of(MembershipRole.OWNER),
                administradora.id().toString());
        String token = buzon.ultimoTokenPara(invitada.email())
                .orElseThrow(() -> new AssertionError("No llego el correo de la invitacion."));

        // Abre el enlace sin estar autenticada: todavia no tiene con que.
        mockMvc.perform(post("/api/v1/invitaciones/apertura")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialSent").value(true));

        // Y ahora entra de verdad. El sujeto del proveedor es nuevo --es su primera vez-- y el
        // correo viene verificado, que es lo que el #13 exige para vincular. Con esto, la cuenta de
        // COPROP que ya existia queda atada a ese sujeto; no nace ninguna segunda.
        String autorizacion = "Bearer " + TestTokenProvider.valido(TestTokenProvider.sujetoNuevo(), invitada.email());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, autorizacion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.id").value(invitada.id().toString()))
                .andExpect(jsonPath("$.person.id").value(personaDeLaInvitada.toString()))
                // Todavia no es miembro de nada: la invitacion no la metio, solo le abrio la puerta.
                .andExpect(jsonPath("$.condominiums.length()").value(0));

        mockMvc.perform(post("/api/v1/invitaciones/aceptacion")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header(HttpHeaders.AUTHORIZATION, autorizacion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk());

        MembershipView nacida = Objects.requireNonNull(
                memberships.of(condominium, invitada.id()), "Aceptar no dejo ninguna membresia.");
        assertThat(nacida.roles()).containsExactly(MembershipRole.OWNER);

        // La misma cuenta y la misma persona que antes de entrar: "no se crea una segunda cuenta".
        AccountView newValues = people.findAccount(invitada.id());
        assertThat(newValues.personId()).isEqualTo(personaDeLaInvitada);
        assertThat(newValues.email()).isEqualTo(invitada.email());

        // Y ahora /me si lo lista, que es lo que vera la pantalla despues de aceptar.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, autorizacion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condominiums.length()").value(1))
                .andExpect(jsonPath("$.condominiums[0].condominium.id").value(condominium.toString()));
    }

    private UUID altaDeCondominio() {
        return condominiums
                .register(
                        new NewCondominium(
                                "Acepta " + UUID.randomUUID(),
                                nitNuevo(),
                                CondominiumType.BUILDING,
                                "America/La_Paz",
                                5,
                                10),
                        "admin")
                .id();
    }

    private static String nitNuevo() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_0000L));
    }

    private AccountView cuentaNueva() {
        PersonView person = people.register(new NewPerson("Emma", "Tola", null), "admin");
        return people.openAccount(person.id(), TestTokenProvider.correoNuevo(), null, "admin");
    }
}
