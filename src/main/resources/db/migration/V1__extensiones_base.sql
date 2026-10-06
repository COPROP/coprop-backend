-- V1: extensiones de PostgreSQL que el modelo necesita.
--
-- En local el usuario del contenedor es superusuario y esto corre sin mas. En una base gestionada
-- de nube hay que conceder antes el rol correspondiente (rds_superuser o equivalente) o pedir al
-- proveedor que habilite estas extensiones.

-- gen_random_uuid() para las claves primarias.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Indices GiST sobre tipos escalares. Lo necesita la restriccion de exclusion que impide el
-- solapamiento de reservas de areas comunes (analisis seccion 7.4, fase 2). Se crea desde ya para
-- no tocar permisos de base cuando llegue esa fase.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- unaccent para busquedas por nombre de unidad o de persona sin acentos.
CREATE EXTENSION IF NOT EXISTS unaccent;
