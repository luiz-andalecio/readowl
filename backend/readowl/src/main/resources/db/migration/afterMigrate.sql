-- Roda ao fim de todo migrate, com o usuário dono do schema, e vale também para as tabelas de migrations futuras.
-- Com um único usuário, como em desenvolvimento e nos testes, não há o que separar.
DO $$
DECLARE
    app_user text := '${appUser}';
BEGIN
    IF app_user = '' OR app_user = current_user THEN
        RETURN;
    END IF;

    -- O dono de uma tabela ignora as permissões abaixo, e a separação pareceria existir sem existir.
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tableowner = app_user) THEN
        RAISE EXCEPTION 'O usuário da aplicação é dono de tabelas: transfira-as para o usuário de migration (REASSIGN OWNED)';
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app_user);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app_user);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app_user);
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app_user);
    -- Só recebem linhas: quem compromete a aplicação não apaga o próprio rastro. A limpeza usa funções do dono (V14).
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM %I', app_user);
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON access_records FROM %I', app_user);
END
$$;
