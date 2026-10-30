INSERT INTO genres (id, version, created_at, updated_at, name)
SELECT uuidv7(), 0, now(), now(), name
FROM unnest(ARRAY[
    'Ação', 'Adulto', 'Alta Fantasia', 'Aventura', 'Autoajuda',
    'Baixa Fantasia', 'Biografia', 'Biopunk', 'Ciência', 'Comédia',
    'Cyberpunk', 'Dieselpunk', 'Distopia', 'Documentário', 'Drama',
    'Ecchi', 'Educativo', 'Espacial', 'Esportes', 'Fantasia',
    'Fantasia Sombria', 'Fantasia Urbana', 'Fatos Reais', 'Ficção Científica', 'Ficção Histórica',
    'Filosófico', 'Futurístico', 'GameLit', 'Gótico', 'Harém',
    'Histórico', 'Horror', 'Isekai', 'LitRPG', 'Lírico',
    'Mecha', 'Militar', 'Mistério', 'Não-Humano', 'Pós-Apocalíptico',
    'Político', 'Psicológico', 'Romance', 'Sátira', 'Seinen',
    'Shonen', 'Shoujo', 'Slice of Life', 'Sobrenatural', 'Steampunk',
    'Suspense', 'Terror', 'Tragédia', 'Vida Escolar', 'Zumbi'
]) AS name;
