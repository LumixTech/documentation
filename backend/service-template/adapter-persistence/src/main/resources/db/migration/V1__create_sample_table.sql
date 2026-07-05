-- Sample aggregate tablosu (şablon). Gerçek serviste kendi şemanla değiştir.
CREATE TABLE sample (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
