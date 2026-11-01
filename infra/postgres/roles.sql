-- Cria o banco e os dois usuários da aplicação. Roda uma vez, com um superusuário, lendo os nomes e as senhas do
-- ambiente, para que não apareçam na linha de comando nem neste arquivo:
--
--   psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f infra/postgres/roles.sql
--
-- As permissões sobre as tabelas vêm de db/migration/afterMigrate.sql, porque as tabelas ainda não existem aqui.
\getenv db_name DB_NAME
\getenv app_user DB_USER
\getenv app_password DB_PASSWORD
\getenv migrator_user DB_MIGRATION_USER
\getenv migrator_password DB_MIGRATION_PASSWORD

CREATE ROLE :"migrator_user" LOGIN PASSWORD :'migrator_password';
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';

-- O dono do banco controla o schema public e pode criar as extensões confiáveis da migration V9.
CREATE DATABASE :"db_name" OWNER :"migrator_user" ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
