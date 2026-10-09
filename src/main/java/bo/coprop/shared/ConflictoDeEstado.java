package bo.coprop.shared;

/**
 * La operacion choca con el estado actual: una transicion que la maquina de estados no permite, o
 * una modificacion concurrente que perdio la carrera.
 */
public class ConflictoDeEstado extends ErrorDeDominio {

    public ConflictoDeEstado(String mensaje) {
        super(CodigoDeError.CONFLICTO, mensaje);
    }
}
