package br.com.readowl.readowl.domain.user;

import br.com.readowl.readowl.domain.book.Book;
import br.com.readowl.readowl.domain.auth.Account;
import br.com.readowl.readowl.domain.auth.PasswordResetToken;
import br.com.readowl.readowl.domain.auth.Session;
import br.com.readowl.readowl.domain.community.BookFollow;
import br.com.readowl.readowl.domain.community.BookRating;
import br.com.readowl.readowl.domain.community.ChapterView;
import br.com.readowl.readowl.domain.community.Comment;
import br.com.readowl.readowl.domain.community.CommentLike;
import br.com.readowl.readowl.domain.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @Email
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, unique = true)
    private String email;

    @Size(max = 255)
    private String password;

    @Size(max = 1000)
    private String image;

    @Size(max = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(nullable = false)
    private int credentialVersion;

    @OneToMany(mappedBy = "author")
    private List<Book> books = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<Account> accounts = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<Session> sessions = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<BookFollow> follows = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<BookRating> ratings = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<Comment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<CommentLike> commentLikes = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<PasswordResetToken> passwordResetTokens = new ArrayList<>();

    @OneToMany(mappedBy = "user")
    private List<ChapterView> chapterViews = new ArrayList<>();
}