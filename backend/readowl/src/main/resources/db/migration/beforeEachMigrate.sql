-- Migrations podem levar mais que uma consulta normal. SET LOCAL vale só para a transação da migration: a conexão
-- volta ao pool com os tempos máximos de sempre. Uma migration fora de transação, como CREATE INDEX CONCURRENTLY,
-- precisa definir o próprio tempo.
SET LOCAL statement_timeout = 0;
SET LOCAL idle_in_transaction_session_timeout = 0;
