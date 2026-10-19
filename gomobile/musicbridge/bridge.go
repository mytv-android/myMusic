// Package musicbridge exposes the vendored music-lib through a gomobile API.
package musicbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/guohuiyuan/music-lib/apple"
	"github.com/guohuiyuan/music-lib/bilibili"
	"github.com/guohuiyuan/music-lib/fivesing"
	"github.com/guohuiyuan/music-lib/jamendo"
	"github.com/guohuiyuan/music-lib/joox"
	"github.com/guohuiyuan/music-lib/kugou"
	"github.com/guohuiyuan/music-lib/kuwo"
	"github.com/guohuiyuan/music-lib/migu"
	"github.com/guohuiyuan/music-lib/model"
	"github.com/guohuiyuan/music-lib/netease"
	"github.com/guohuiyuan/music-lib/qianqian"
	"github.com/guohuiyuan/music-lib/qq"
	"github.com/guohuiyuan/music-lib/soda"
)

const sourceRevision = "7cc4467b4e300547edd5d3d5bf0eff1c21ffc2ec"

var providers = []ProviderInfo{
	{ID: "netease", Name: "网易云音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, RecommendedPlaylists: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, UserPlaylists: true, QRLogin: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true},
	{ID: "qq", Name: "QQ 音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, RecommendedPlaylists: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, UserPlaylists: true, QRLogin: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true},
	{ID: "kugou", Name: "酷狗音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, RecommendedPlaylists: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, UserPlaylists: true, QRLogin: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true},
	{ID: "kuwo", Name: "酷我音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, RecommendedPlaylists: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true},
	{ID: "migu", Name: "咪咕音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, RecommendedPlaylists: false, PlaylistSongs: true, ParsePlaylist: true, SearchPlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true},
	{ID: "qianqian", Name: "千千音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, PlaylistCategories: true, Unstable: "playlist-search"},
	{ID: "soda", Name: "汽水音乐", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, UserPlaylists: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true, Note: "会员整曲依赖官方客户端签名器，Android 上不可用；未登录时可能只返回试听片段"},
	{ID: "fivesing", Name: "5sing", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true},
	{ID: "jamendo", Name: "Jamendo", Search: true, Download: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, Unstable: "playlist-search"},
	{ID: "joox", Name: "JOOX", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, PlaylistCategories: true},
	{ID: "bilibili", Name: "Bilibili", Search: true, Download: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, QRLogin: true},
	{ID: "apple", Name: "Apple Music", Search: true, Download: true, Lyrics: true, ParseSong: true, SearchPlaylist: true, PlaylistSongs: true, ParsePlaylist: true, SearchAlbum: true, AlbumSongs: true, ParseAlbum: true, PlaylistCategories: true, TopLists: true, TopListSongs: true, HotSongs: true, Note: "仅提供试听预览，完整音频需要上游声明的独立解密工具"},
}

type ProviderInfo struct {
	ID                    string
	Name                  string
	Search                bool
	Download              bool
	Lyrics                bool
	ParseSong             bool
	SearchPlaylist        bool
	RecommendedPlaylists  bool
	PlaylistSongs         bool
	ParsePlaylist         bool
	SearchAlbum           bool
	AlbumSongs            bool
	ParseAlbum            bool
	UserPlaylists         bool
	QRLogin               bool
	PlaylistCategories    bool
	TopLists              bool
	TopListSongs          bool
	HotSongs              bool
	Unstable              string
	Note                  string
}

type client struct {
	searcher     func(string) ([]model.Song, error)
	songParser   func(string) (*model.Song, error)
	downloader   func(*model.Song) (string, error)
	lyrics       func(*model.Song) (string, error)
	albumSearch  func(string) ([]model.Playlist, error)
	albumSongs   func(string) ([]model.Song, error)
	albumParser  func(string) (*model.Playlist, []model.Song, error)
	listSearch   func(string) ([]model.Playlist, error)
	listSongs    func(string) ([]model.Song, error)
	listParser   func(string) (*model.Playlist, []model.Song, error)
	recommended  func() ([]model.Playlist, error)
	categories   func() ([]model.PlaylistCategory, error)
	categoryList func(string, int, int) ([]model.Playlist, error)
	userLists    func(int, int) ([]model.Playlist, error)
	topLists     func() ([]model.TopList, error)
	topSongs     func(string) ([]model.Song, error)
	hotSongs     func(int) ([]model.Song, error)
	qrCreate     func() (*model.QRLoginSession, error)
	qrCheck      func(string) (*model.QRLoginResult, error)
}

var (
	sessionMu sync.Mutex
	sessions  = map[string]func(string) (*model.QRLoginResult, error){}
	nextID    int
)

func SourceRevision() string { return sourceRevision }

func ProvidersJSON() (string, error) { return encode(providers) }

func SearchSongs(providerID, cookie, keyword string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.searcher, keyword)
}

func ParseSong(providerID, cookie, link string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	song, callErr := c.songParser(link)
	return result(song, callErr)
}

func DownloadURL(providerID, cookie, songJSON string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	var song model.Song
	if err = json.Unmarshal([]byte(songJSON), &song); err != nil {
		return "", err
	}
	return c.downloader(&song)
}

func Lyrics(providerID, cookie, songJSON string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	var song model.Song
	if err = json.Unmarshal([]byte(songJSON), &song); err != nil {
		return "", err
	}
	return c.lyrics(&song)
}

func SearchPlaylists(providerID, cookie, keyword string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.listSearch, keyword)
}

func PlaylistSongs(providerID, cookie, playlistID string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.listSongs, playlistID)
}

func ParsePlaylist(providerID, cookie, link string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return collection(c.listParser(link))
}

func RecommendedPlaylists(providerID, cookie string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.recommended())
}

func SearchAlbums(providerID, cookie, keyword string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.albumSearch, keyword)
}

func AlbumSongs(providerID, cookie, albumID string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.albumSongs, albumID)
}

func ParseAlbum(providerID, cookie, link string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return collection(c.albumParser(link))
}

func PlaylistCategories(providerID, cookie string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.categories())
}

func CategoryPlaylists(providerID, cookie, categoryID string, page, limit int) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.categoryList(categoryID, page, limit))
}

func UserPlaylists(providerID, cookie string, page, limit int) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.userLists(page, limit))
}

func TopLists(providerID, cookie string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.topLists())
}

func TopListSongs(providerID, cookie, topListID string) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return call(c.topSongs, topListID)
}

func HotSongs(providerID, cookie string, limit int) (string, error) {
	c, err := open(providerID, cookie)
	if err != nil {
		return "", err
	}
	return result(c.hotSongs(limit))
}

func CreateQRLogin(providerID, loginType string) (string, error) {
	c, err := open(providerID, "")
	if err != nil {
		return "", err
	}
	session, callErr := c.qrCreate()
	if providerID == "qq" && loginType == "wx" {
		session, callErr = qq.CreateWXQRLogin()
		c.qrCheck = qq.CheckWXQRLogin
	}
	if callErr != nil {
		return "", callErr
	}
	sessionMu.Lock()
	nextID++
	id := fmt.Sprintf("%s-%d", providerID, nextID)
	sessions[id] = c.qrCheck
	sessionMu.Unlock()
	return encode(map[string]any{"session_id": id, "session": session})
}

func CheckQRLogin(sessionID, key string) (string, error) {
	sessionMu.Lock()
	check := sessions[sessionID]
	sessionMu.Unlock()
	if check == nil {
		return "", errors.New("login session not found")
	}
	return result(check(key))
}

func open(providerID, cookie string) (client, error) {
	switch strings.ToLower(strings.TrimSpace(providerID)) {
	case "netease":
		p := netease.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, p.GetRecommendedPlaylists, p.GetPlaylistCategories, p.GetCategoryPlaylists, p.GetUserPlaylists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, p.CreateQRLogin, p.CheckQRLogin}, nil
	case "qq":
		p := qq.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, p.GetRecommendedPlaylists, p.GetPlaylistCategories, p.GetCategoryPlaylists, p.GetUserPlaylists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, p.CreateQRLogin, p.CheckQRLogin}, nil
	case "kugou":
		p := kugou.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, p.GetRecommendedPlaylists, p.GetPlaylistCategories, p.GetCategoryPlaylists, p.GetUserPlaylists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, p.CreateQRLogin, p.CheckQRLogin}, nil
	case "kuwo":
		p := kuwo.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, p.GetRecommendedPlaylists, p.GetPlaylistCategories, p.GetCategoryPlaylists, unsupportedLists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "migu":
		p := migu.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, p.GetPlaylistCategories, p.GetCategoryPlaylists, unsupportedLists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "qianqian":
		p := qianqian.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, p.GetPlaylistCategories, p.GetCategoryPlaylists, unsupportedLists, unsupportedTopLists, unsupportedTopSongs, unsupportedHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "soda":
		p := soda.New(cookie)
		p.ClearLocalSigner()
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, p.GetPlaylistCategories, p.GetCategoryPlaylists, p.GetUserPlaylists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "fivesing":
		p := fivesing.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, unsupportedAlbums, unsupportedAlbumSongs, unsupportedAlbumParse, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, unsupportedCategories, unsupportedCategoryLists, unsupportedLists, unsupportedTopLists, unsupportedTopSongs, unsupportedHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "jamendo":
		p := jamendo.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, emptyLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, unsupportedCategories, unsupportedCategoryLists, unsupportedLists, unsupportedTopLists, unsupportedTopSongs, unsupportedHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "joox":
		p := joox.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, p.GetPlaylistCategories, p.GetCategoryPlaylists, unsupportedLists, unsupportedTopLists, unsupportedTopSongs, unsupportedHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	case "bilibili":
		p := bilibili.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, emptyLyrics, unsupportedAlbums, unsupportedAlbumSongs, unsupportedAlbumParse, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, unsupportedCategories, unsupportedCategoryLists, unsupportedLists, unsupportedTopLists, unsupportedTopSongs, unsupportedHotSongs, p.CreateQRLogin, p.CheckQRLogin}, nil
	case "apple":
		p := apple.New(cookie)
		return client{p.Search, p.Parse, p.GetDownloadURL, p.GetLyrics, p.SearchAlbum, p.GetAlbumSongs, p.ParseAlbum, p.SearchPlaylist, p.GetPlaylistSongs, p.ParsePlaylist, unsupportedRecommended, p.GetPlaylistCategories, p.GetCategoryPlaylists, unsupportedLists, p.GetTopLists, p.GetTopListSongs, p.GetHotSongs, unsupportedQR, unsupportedQRCheck}, nil
	default:
		return client{}, fmt.Errorf("unsupported provider: %s", providerID)
	}
}

func unsupportedLists(int, int) ([]model.Playlist, error) { return nil, model.ErrUserPlaylistsUnsupported }
func unsupportedRecommended() ([]model.Playlist, error) { return nil, errors.New("recommended playlists not supported") }
func unsupportedCategories() ([]model.PlaylistCategory, error) { return nil, model.ErrPlaylistCategoriesUnsupported }
func unsupportedCategoryLists(string, int, int) ([]model.Playlist, error) { return nil, model.ErrPlaylistCategoriesUnsupported }
func unsupportedAlbums(string) ([]model.Playlist, error) { return nil, errors.New("albums not supported") }
func unsupportedAlbumSongs(string) ([]model.Song, error) { return nil, errors.New("albums not supported") }
func unsupportedAlbumParse(string) (*model.Playlist, []model.Song, error) { return nil, nil, errors.New("albums not supported") }
func unsupportedTopLists() ([]model.TopList, error) { return nil, model.ErrTopListsUnsupported }
func unsupportedTopSongs(string) ([]model.Song, error) { return nil, model.ErrTopListsUnsupported }
func unsupportedHotSongs(int) ([]model.Song, error) { return nil, model.ErrHotSongsUnsupported }
func unsupportedQR() (*model.QRLoginSession, error) { return nil, errors.New("qr login not supported") }
func unsupportedQRCheck(string) (*model.QRLoginResult, error) { return nil, errors.New("qr login not supported") }
func emptyLyrics(*model.Song) (string, error) { return "", nil }

func call[T any](fn func(string) (T, error), value string) (string, error) {
	if fn == nil {
		return "", errors.New("operation not supported")
	}
	return result(fn(value))
}

func collection(item *model.Playlist, songs []model.Song, err error) (string, error) {
	if err != nil {
		return "", err
	}
	return encode(map[string]any{"item": item, "songs": songs})
}

func result(value any, err error) (string, error) {
	if err != nil {
		return "", err
	}
	return encode(value)
}

func encode(value any) (string, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
