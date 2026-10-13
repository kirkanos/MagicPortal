package main

import (
	"net/http"
	"sort"
)

// Reserved List overview: which cards from Wizards' Reserved List (Scryfall's
// `reserved` flag) are owned vs. missing, the market value of the owned ones and
// how much it would cost to buy one copy of every missing card (cheapest
// printing). A Reserved-List card is identified by name – all its printings are
// reserved, so owning any printing counts.

type reservedCard struct {
	Name          string   `json:"name"`
	Owned         bool     `json:"owned"`
	Quantity      int      `json:"quantity"`
	OwnedValue    float64  `json:"ownedValue"`
	CheapestPrice *float64 `json:"cheapestPrice"`
	// Just the id, as everywhere else, so the images can go through the local
	// mirror; empty when the catalog carries no image.
	ImageID       string `json:"imageId"`
	Rarity        string `json:"rarity"`
	CardmarketURI string `json:"cardmarketUri"`
}

func handleReserved(w http.ResponseWriter, r *http.Request) {
	// 1) All reserved printings → cheapest price + a representative image per
	// name. Shared with the wishlist: the same question, the same answer.
	master, err := cheapestByName("c.reserved = 1")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// 2) Owned reserved cards: quantity + foil-aware market value per name.
	ownedRows, err := db.Query(`
		SELECT s.name, SUM(c.quantity),
		       COALESCE(SUM(c.quantity * (` + foilAwareMarket + `)), 0)
		FROM collection c
		JOIN scryfall_cards s ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		WHERE s.reserved = 1
		GROUP BY s.name`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	type owned struct {
		qty   int
		value float64
	}
	ownedMap := map[string]owned{}
	for ownedRows.Next() {
		var name string
		var qty int
		var value float64
		if err := ownedRows.Scan(&name, &qty, &value); err != nil {
			ownedRows.Close()
			http.Error(w, err.Error(), 500)
			return
		}
		ownedMap[name] = owned{qty: qty, value: value}
	}
	ownedRows.Close()

	// 3) Merge + summary.
	cards := make([]reservedCard, 0, len(master))
	var ownedCount, missingUnpriced int
	var ownedValue, costToComplete float64
	for name, a := range master {
		rc := reservedCard{
			Name:          name,
			CheapestPrice: a.Price,
			ImageID:       a.ImageID,
			Rarity:        a.Rarity,
			CardmarketURI: a.CardmarketURI,
		}
		if o, ok := ownedMap[name]; ok {
			rc.Owned = true
			rc.Quantity = o.qty
			rc.OwnedValue = o.value
			ownedCount++
			ownedValue += o.value
		} else {
			if a.Price != nil {
				costToComplete += *a.Price
			} else {
				missingUnpriced++
			}
		}
		cards = append(cards, rc)
	}

	// Default order: most expensive first (by cheapest acquisition price).
	sort.Slice(cards, func(i, j int) bool {
		pi, pj := 0.0, 0.0
		if cards[i].CheapestPrice != nil {
			pi = *cards[i].CheapestPrice
		}
		if cards[j].CheapestPrice != nil {
			pj = *cards[j].CheapestPrice
		}
		if pi != pj {
			return pi > pj
		}
		return cards[i].Name < cards[j].Name
	})

	total := len(master)
	writeJSON(w, map[string]interface{}{
		"summary": map[string]interface{}{
			"total":           total,
			"owned":           ownedCount,
			"missing":         total - ownedCount,
			"ownedValue":      ownedValue,
			"costToComplete":  costToComplete,
			"missingUnpriced": missingUnpriced,
		},
		"cards": cards,
	})
}
