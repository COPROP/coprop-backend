-- V15: el responsable de pago por defecto es el propietario. Issue #29.
--
-- RF-08 y la decision 13.3 del analisis lo dicen igual: "el propietario, para todos los conceptos;
-- el inquilino solo si se configura explicitamente". Hasta ahora eso no estaba escrito en ninguna
-- parte --billing_responsibility solo tenia las filas que alguien asignaba a mano-- y la emision de
-- expensas del #24 se detenia entera en cuanto una unidad no tenia ninguna.
--
-- SE DECIDIO QUE EL DEFECTO ES UNA FILA DE VERDAD y no una regla que se resuelve al emitir
-- (23-oct-2026). Tres razones:
--
-- 1. El analisis 7.1 dice que la emision asigna el responsable "segun BillingResponsibility", sin
--    mas. Con una regla implicita, esa tabla dejaria de contestar la pregunta sola.
-- 2. "Quien paga esta unidad" se contesta con una consulta, que es lo que la web necesita pintar.
-- 3. Seguridad 2 construye el acceso del inquilino sobre "figura como responsable en
--    BillingResponsibility vigente". Si el propietario no figurara, las dos mitades de esa frase
--    se comportarian distinto.
--
-- A partir de aqui la fila la abre UnitRelations: al registrar al propietario y al transferir la
-- unidad. Esta migracion es solo el relleno de lo que ya existe.
--
-- LA COPROPIEDAD NO RECIBE DEFECTO, Y TAMBIEN SE DECIDIO (23-oct-2026): con dos o mas propietarios
-- vigentes no se adivina a cual de ellos se le cobra. La obligacion tiene UN responsable, y elegir
-- al del porcentaje mayor seria decidir a quien se le reclama el dinero sin que nadie lo haya
-- dicho. Esas unidades se configuran a mano, y la emision del #24 las nombra en su mensaje.

INSERT INTO billing_responsibility (id, condominium_id, unit_id, person_id, concept_id, valid_from)
SELECT gen_random_uuid(),
       propietaria.condominium_id,
       propietaria.unit_id,
       propietaria.person_id,
       -- Responsable general: de todo lo que se cobre a la unidad. Es lo que significa "para todos
       -- los conceptos" en la decision 13.3.
       NULL,
       -- Desde cuando lo es: desde que es propietaria, no desde que corre esta migracion. Si se
       -- pusiera "ahora", la vigencia diria que la unidad estuvo sin responsable hasta hoy, y eso
       -- no es lo que paso: estuvo sin la fila que lo decia.
       propietaria.valid_from
FROM unit_ownership propietaria
WHERE propietaria.valid_to IS NULL
  -- Exactamente una propietaria vigente. Con dos o mas no hay defecto.
  AND (
      SELECT count(*)
      FROM unit_ownership otra
      WHERE otra.unit_id = propietaria.unit_id
        AND otra.valid_to IS NULL
  ) = 1
  -- Y la unidad no tiene ya un responsable general vigente, sea de quien sea: lo configurado a mano
  -- manda sobre el defecto, y volver a abrirlo chocaria con el indice unico parcial de la V5.
  AND NOT EXISTS (
      SELECT 1
      FROM billing_responsibility existente
      WHERE existente.unit_id = propietaria.unit_id
        AND existente.concept_id IS NULL
        AND existente.valid_to IS NULL
  );

COMMENT ON COLUMN billing_responsibility.person_id IS
    'Quien responde. Por defecto la propietaria unica de la unidad (analisis 13.3, issue #29).';
