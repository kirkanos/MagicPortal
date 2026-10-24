package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// testdata/dhash holds grayscale cards as PGM plus the hashes they must give.
// The Android scanner tests read the same files (copied to its test
// resources), so the two implementations cannot drift apart unnoticed.
// PGM rather than JPEG: JPEG decoders differ by a level here and there, and
// the point of these cases is exact equality.

type dhashCase struct {
	File string `json:"file"`
	H    string `json:"h"`
	V    string `json:"v"`
}

func readPGM(path string) (grayImage, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return grayImage{}, err
	}
	r := bufio.NewReader(bytes.NewReader(data))
	var magic string
	var w, h, maxv int
	if _, err := fmt.Fscan(r, &magic, &w, &h, &maxv); err != nil || magic != "P5" || maxv != 255 {
		return grayImage{}, fmt.Errorf("%s: not an 8-bit P5 file", path)
	}
	if _, err := r.ReadByte(); err != nil { // the single whitespace after the header
		return grayImage{}, err
	}
	pix := make([]uint8, w*h)
	if _, err := r.Read(pix[:0]); err != nil {
		return grayImage{}, err
	}
	n := 0
	for n < len(pix) {
		k, err := r.Read(pix[n:])
		if err != nil {
			return grayImage{}, err
		}
		n += k
	}
	return grayImage{w: w, h: h, pix: pix}, nil
}

func writePGM(path string, g grayImage) error {
	var b bytes.Buffer
	fmt.Fprintf(&b, "P5\n%d %d\n255\n", g.w, g.h)
	b.Write(g.pix)
	return os.WriteFile(path, b.Bytes(), 0o644)
}

func loadDhashCases(t *testing.T) []dhashCase {
	t.Helper()
	data, err := os.ReadFile("testdata/dhash/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []dhashCase
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	return cases
}

func TestDhashFixtures(t *testing.T) {
	for _, c := range loadDhashCases(t) {
		g, err := readPGM(filepath.Join("testdata/dhash", c.File))
		if err != nil {
			t.Fatal(err)
		}
		h, v := g.dhash()
		if got := fmt.Sprintf("%016x", h); got != c.H {
			t.Errorf("%s: h = %s, want %s", c.File, got, c.H)
		}
		if got := fmt.Sprintf("%016x", v); got != c.V {
			t.Errorf("%s: v = %s, want %s", c.File, got, c.V)
		}
	}
}

// Different cards are far apart, the same card through a lossy JPEG round
// trip stays close.
func TestDhashSeparatesCards(t *testing.T) {
	cases := loadDhashCases(t)
	type hv struct{ h, v uint64 }
	hashes := map[string]hv{}
	for _, c := range cases {
		g, err := readPGM(filepath.Join("testdata/dhash", c.File))
		if err != nil {
			t.Fatal(err)
		}
		h, v := g.dhash()
		hashes[c.File] = hv{h, v}

		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, toImage(g), &jpeg.Options{Quality: 60}); err != nil {
			t.Fatal(err)
		}
		img, err := jpeg.Decode(&buf)
		if err != nil {
			t.Fatal(err)
		}
		h2, v2 := toGray(img).dhash()
		if d := dhashDistance(h, v, h2, v2); d > 8 {
			t.Errorf("%s: JPEG round trip moved the hash by %d bits", c.File, d)
		}
	}
	for i, a := range cases {
		for _, b := range cases[i+1:] {
			x, y := hashes[a.File], hashes[b.File]
			if d := dhashDistance(x.h, x.v, y.h, y.v); d < 40 {
				t.Errorf("%s vs %s: only %d bits apart", a.File, b.File, d)
			}
		}
	}
}

func encodeJPEG(t *testing.T, g grayImage) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, toImage(g), &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func toImage(g grayImage) image.Image {
	img := image.NewGray(image.Rect(0, 0, g.w, g.h))
	copy(img.Pix, g.pix)
	return img
}

// TestGenerateDhashFixtures rebuilds testdata/dhash from a directory of card
// JPEGs: DHASH_SOURCE=/path/to/jpgs go test -run GenerateDhash. Only for
// adding cases; afterwards copy the directory to the Android test resources.
func TestGenerateDhashFixtures(t *testing.T) {
	src := os.Getenv("DHASH_SOURCE")
	if src == "" {
		t.Skip("DHASH_SOURCE not set")
	}
	files, _ := filepath.Glob(filepath.Join(src, "*.jpg"))
	sort.Strings(files)
	var cases []dhashCase
	for _, f := range files {
		fh, err := os.Open(f)
		if err != nil {
			t.Fatal(err)
		}
		img, err := jpeg.Decode(fh)
		fh.Close()
		if err != nil {
			t.Fatal(err)
		}
		g := toGray(img)
		name := strings.TrimSuffix(filepath.Base(f), ".jpg") + ".pgm"
		if err := writePGM(filepath.Join("testdata/dhash", name), g); err != nil {
			t.Fatal(err)
		}
		h, v := g.dhash()
		cases = append(cases, dhashCase{name, fmt.Sprintf("%016x", h), fmt.Sprintf("%016x", v)})
	}
	out, _ := json.MarshalIndent(cases, "", "  ")
	if err := os.WriteFile("testdata/dhash/cases.json", append(out, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
}
