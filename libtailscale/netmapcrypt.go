// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// OpenRoute: encryption for the cached tailnet map.

package libtailscale

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"fmt"
	"iter"

	"tailscale.com/ipn/ipnlocal/netmapcache"
)

// netmapKeyPref is the encrypted preference holding the netmap cache key.
const netmapKeyPref = "openroute-netmap-cache-key"

// netmapCacheHook returns an ipnlocal.NetmapCacheStoreHook that seals the
// cached tailnet map with AES-256-GCM. The key lives in the app's
// EncryptedSharedPreferences (via store), which is protected by the Android
// Keystore, so the cache files alone are unreadable.
func netmapCacheHook(store *stateStore) func(netmapcache.Store) netmapcache.Store {
	aead, err := netmapCacheAEAD(store)
	return func(s netmapcache.Store) netmapcache.Store {
		return &sealedStore{inner: s, aead: aead, err: err}
	}
}

func netmapCacheAEAD(store *stateStore) (cipher.AEAD, error) {
	key, err := store.read(netmapKeyPref)
	if err != nil {
		return nil, fmt.Errorf("reading netmap cache key: %w", err)
	}
	if len(key) != 32 {
		key = make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return nil, err
		}
		if err := store.write(netmapKeyPref, key); err != nil {
			return nil, fmt.Errorf("saving netmap cache key: %w", err)
		}
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// sealedStore is a netmapcache.Store that encrypts values, binding each to
// its key. If the cipher couldn't be set up (err), nothing is stored or
// loaded, so the cache is never written in the clear.
type sealedStore struct {
	inner netmapcache.Store
	aead  cipher.AEAD
	err   error
}

var errSealed = errors.New("netmap cache: cannot decrypt")

func (s *sealedStore) List(ctx context.Context, prefix string) iter.Seq2[string, error] {
	return s.inner.List(ctx, prefix)
}

func (s *sealedStore) Remove(ctx context.Context, key string) error {
	return s.inner.Remove(ctx, key)
}

func (s *sealedStore) Load(ctx context.Context, key string) ([]byte, error) {
	if s.err != nil {
		return nil, s.err
	}
	b, err := s.inner.Load(ctx, key)
	if err != nil {
		return nil, err
	}
	n := s.aead.NonceSize()
	if len(b) < n {
		return nil, errSealed
	}
	pt, err := s.aead.Open(nil, b[:n], b[n:], []byte(key))
	if err != nil {
		return nil, errSealed
	}
	return pt, nil
}

func (s *sealedStore) Store(ctx context.Context, key string, value []byte) error {
	if s.err != nil {
		return s.err
	}
	nonce := make([]byte, s.aead.NonceSize(), s.aead.NonceSize()+len(value)+s.aead.Overhead())
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	return s.inner.Store(ctx, key, s.aead.Seal(nonce, nonce, value, []byte(key)))
}
