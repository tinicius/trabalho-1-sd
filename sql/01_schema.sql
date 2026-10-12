-- Esquema do Banco_de_Dados (PostgreSQL) do chat em grupo distribuido.
--
-- Uso (em um banco vazio):
--   psql -h localhost -U <usuario> -d <banco> -f sql/01_schema.sql
--
-- A unicidade do login e garantida pela restricao UNIQUE: a violacao
-- (SQLState 23505) vira LOGIN_DUPLICADO no Middleware, sem consulta previa
-- e sem condicao de corrida entre cadastros simultaneos.

CREATE TABLE contas (
    id          BIGSERIAL    PRIMARY KEY,
    login       VARCHAR(20)  NOT NULL,
    apelido     VARCHAR(30)  NOT NULL,
    senha_hash  VARCHAR(200) NOT NULL,          -- sha256i$N$salt$hash (nunca a senha legivel)
    avatar      VARCHAR(5)   NOT NULL,          -- av_01 .. av_12
    cor         CHAR(7)      NOT NULL,          -- #RRGGBB maiusculo
    criada_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_contas_login UNIQUE (login),
    CONSTRAINT ck_login  CHECK (login  ~ '^[a-z0-9_]{3,20}$'),
    CONSTRAINT ck_avatar CHECK (avatar ~ '^av_[0-9]{2}$'),
    CONSTRAINT ck_cor    CHECK (cor    ~ '^#[0-9A-F]{6}$')
);
