package main

import (
	"image"
	"math/bits"
)

// Difference hash of a card's artwork, for the Android scanner.
//
// Cards printed before collector numbers (and foreign cards, whose names the
// catalog does not know) can only be told apart by their picture. The scanner
// hashes the camera image of a card and looks for the nearest hash in an
// index this backend builds from Scryfall's scans.
//
// The hash covers an inset box of the artwork, x in [10%, 90%), y in [12%,
// 50%) of the card, which lies inside the art of every frame from Alpha to
// today. It is a 128-bit dHash: the box is averaged down to 9x8 cells, each
// bit says whether a cell is brighter than its left neighbour (64 bits), and
// again on 8x9 cells against the upper neighbour (64 bits). Distance is the
// number of differing bits.
//
// Not pHash: its DCT runs in floating point and is not bit-identical between
// Go on amd64, Go on arm64 and the JVM (fused multiply-add). Everything here
// is integer arithmetic, and the Kotlin twin (scanner/hash/DHash.kt) must
// produce exactly the same bits - testdata/dhash holds the shared cases.

const (
	dhashX0, dhashX1 = 10, 90 // percent of the card width
	dhashY0, dhashY1 = 12, 50 // percent of the card height
)

// grayImage is an 8-bit luminance raster.
type grayImage struct {
	w, h int
	pix  []uint8
}

// toGray converts with integer BT.601 weights, the same formula the phone uses.
func toGray(img image.Image) grayImage {
	b := img.Bounds()
	g := grayImage{w: b.Dx(), h: b.Dy(), pix: make([]uint8, b.Dx()*b.Dy())}
	for y := 0; y < g.h; y++ {
		for x := 0; x < g.w; x++ {
			r, gr, bl, _ := img.At(b.Min.X+x, b.Min.Y+y).RGBA()
			g.pix[y*g.w+x] = uint8((299*(r>>8) + 587*(gr>>8) + 114*(bl>>8) + 500) / 1000)
		}
	}
	return g
}

// cells averages the hash box into cols x rows cells. Cell boundaries are
// integer divisions of the box, every pixel belongs to exactly one cell.
func (g grayImage) cells(cols, rows int) []int {
	x0, x1 := g.w*dhashX0/100, g.w*dhashX1/100
	y0, y1 := g.h*dhashY0/100, g.h*dhashY1/100
	bw, bh := x1-x0, y1-y0
	out := make([]int, cols*rows)
	for r := 0; r < rows; r++ {
		ya, yb := y0+r*bh/rows, y0+(r+1)*bh/rows
		for c := 0; c < cols; c++ {
			xa, xb := x0+c*bw/cols, x0+(c+1)*bw/cols
			sum, n := 0, 0
			for y := ya; y < yb; y++ {
				row := g.pix[y*g.w:]
				for x := xa; x < xb; x++ {
					sum += int(row[x])
					n++
				}
			}
			if n > 0 {
				out[r*cols+c] = sum / n
			}
		}
	}
	return out
}

// dhash returns the horizontal and the vertical half. Bit 63 is the first
// cell, row by row.
func (g grayImage) dhash() (h, v uint64) {
	hc := g.cells(9, 8)
	for r := 0; r < 8; r++ {
		for c := 0; c < 8; c++ {
			if hc[r*9+c+1] > hc[r*9+c] {
				h |= 1 << (63 - (r*8 + c))
			}
		}
	}
	vc := g.cells(8, 9)
	for r := 0; r < 8; r++ {
		for c := 0; c < 8; c++ {
			if vc[(r+1)*8+c] > vc[r*8+c] {
				v |= 1 << (63 - (r*8 + c))
			}
		}
	}
	return h, v
}

func dhashDistance(h1, v1, h2, v2 uint64) int {
	return bits.OnesCount64(h1^h2) + bits.OnesCount64(v1^v2)
}
