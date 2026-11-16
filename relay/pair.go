package main

// Liaison au compte par code : le PC affiche un code, un appareil déjà connecté (« Connecter un appareil »)
// y envoie les identifiants chiffrés. Même protocole que les applis Android et LG.

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
)

const pairABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

func newPairCode() string {
	b := make([]byte, 8)
	for i := range b {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(pairABC))))
		b[i] = pairABC[n.Int64()]
	}
	return string(b)
}

func pairURL(code string) string {
	return dbURL + "/h/p" + hex.EncodeToString(sha("koalatv-pair|"+code))[:40] + "/x.json"
}

func pairXor(code string, data []byte) []byte {
	out := make([]byte, len(data))
	var block []byte
	for i := range data {
		if i%32 == 0 {
			block = sha("koalatv-key|" + code + "|" + itoa(i/32))
		}
		out[i] = data[i] ^ block[i%32]
	}
	return out
}

func itoa(n int) string { return big.NewInt(int64(n)).String() }

type Creds struct {
	Server string `json:"s"`
	User   string `json:"u"`
	Pass   string `json:"p"`
}

var errNoPair = errors.New("rien reçu")

// pairReceive lit (puis efface) les identifiants envoyés pour ce code.
func pairReceive(code string) (*Creds, error) {
	b, err := fbDo("GET", pairURL(code), nil)
	if err != nil {
		return nil, err
	}
	var rec struct {
		D string `json:"d"`
	}
	if string(b) == "null" || json.Unmarshal(b, &rec) != nil || rec.D == "" {
		return nil, errNoPair
	}
	fbDo("DELETE", pairURL(code), nil)
	raw, err := hex.DecodeString(rec.D)
	if err != nil {
		return nil, err
	}
	var c Creds
	if err := json.Unmarshal(pairXor(code, raw), &c); err != nil || c.Server == "" || c.User == "" || c.Pass == "" {
		return nil, errors.New("identifiants illisibles")
	}
	return &c, nil
}
