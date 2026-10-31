package br.com.readowl.readowl.book.api;

final class BookRules {

    static final String NOT_BLANK = "(?s).*\\S.*";
    static final String ACRONYM = "[A-Za-z0-9]{2,10}";
    static final String ACRONYM_MESSAGE = "deve ter de 2 a 10 letras ou números, sem acentos ou espaços";
    static final String TAG = "\\s*[\\p{L}\\p{N}][\\p{L}\\p{N} _-]*\\s*";
    static final String TAG_MESSAGE = "deve ter apenas letras, números, espaços, _ ou -";

    private BookRules() {
    }
}
