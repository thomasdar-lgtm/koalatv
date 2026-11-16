package main

import "strconv"

func strconvF(f float64) string { return strconv.FormatFloat(f, 'f', 1, 64) }
