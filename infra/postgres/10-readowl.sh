#!/bin/sh
# Executado pela imagem do PostgreSQL só na primeira inicialização, com o volume vazio.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres --file /readowl/roles.sql
