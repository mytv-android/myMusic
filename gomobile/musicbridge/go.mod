module github.com/mytv-android/myMusic/musicbridge

go 1.26.0

require github.com/guohuiyuan/music-lib v0.0.0

require (
	golang.org/x/mobile v0.0.0-20261011034528-197770444867 // indirect
	golang.org/x/mod v0.42.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/text v0.39.0 // indirect
	golang.org/x/tools v0.52.0 // indirect
)

replace github.com/guohuiyuan/music-lib => ../../third_party/music-lib

tool golang.org/x/mobile/cmd/gobind
