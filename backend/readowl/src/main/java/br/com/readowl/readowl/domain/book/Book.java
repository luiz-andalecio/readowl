package br.com.readowl.readowl.domain.book;

import br.com.readowl.readowl.domain.genre.Genre;
import br.com.readowl.readowl.domain.community.BookFollow;
import br.com.readowl.readowl.domain.community.BookRating;
import br.com.readowl.readowl.domain.community.Comment;
import br.com.readowl.readowl.domain.publishing.Chapter;
import br.com.readowl.readowl.domain.publishing.Volume;
import br.com.readowl.readowl.domain.shared.BaseEntity;
import br.com.readowl.readowl.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "books")
@Getter
@Setter
@NoArgsConstructor
public class Book extends BaseEntity {

	@NotBlank
	@Size(max = 200)
	@Column(nullable = false, length = 200)
	private String title;

	@NotBlank
	@Size(max = 2000)
	@Column(nullable = false, length = 2000)
	private String synopsis;

	@Size(max = 50)
	@Column(length = 50)
	private String releaseFrequency;

	@Size(max = 1000)
	@Column(length = 1000)
	private String coverUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookStatus status = BookStatus.ONGOING;

	@Column(nullable = false)
	private int views;

	@NotNull
	@ManyToOne(optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@ManyToMany
	@JoinTable(name = "book_genres",
			joinColumns = @JoinColumn(name = "book_id"),
			inverseJoinColumns = @JoinColumn(name = "genre_id"))
	private List<Genre> genres = new ArrayList<>();

	@OneToMany(mappedBy = "book")
	private List<BookFollow> followers = new ArrayList<>();

	@OneToMany(mappedBy = "book")
	private List<BookRating> ratings = new ArrayList<>();

	@OneToMany(mappedBy = "book")
	private List<Volume> volumes = new ArrayList<>();

	@OneToMany(mappedBy = "book")
	private List<Chapter> chapters = new ArrayList<>();

	@OneToMany(mappedBy = "book")
	private List<Comment> comments = new ArrayList<>();
}
