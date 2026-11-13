// Package tunnel implements the reverse UDP tunnel used to expose the
// Minecraft Bedrock server without touching the user's router.
//
// The phone opens an outbound TCP connection to a relay; the relay allocates a
// public UDP port; each player that sends a datagram to that port gets a flow,
// and every datagram travels inside the control connection to the phone, where
// it is written to the game server through a dedicated local UDP socket (one
// socket per flow, so RakNet still sees one address per player).
//
// The protocol is deliberately small: a text handshake followed by
// length-prefixed frames.
package tunnel

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
)

const (
	// Handshake is the first line the client writes: "MOBOTUN/1 <token>".
	handshakePrefix = "MOBOTUN/1 "

	frameData  byte = 1 // datagram payload
	frameClose byte = 2 // flow finished

	// maxFrameSize covers the largest RakNet datagram (usually under 1500
	// bytes) with room to spare; anything bigger is a protocol error.
	maxFrameSize = 4096
)

func writeFrame(w io.Writer, typ byte, flow uint16, payload []byte) error {
	buf := make([]byte, 7+len(payload))
	binary.BigEndian.PutUint32(buf[0:4], uint32(3+len(payload)))
	buf[4] = typ
	binary.BigEndian.PutUint16(buf[5:7], flow)
	copy(buf[7:], payload)
	_, err := w.Write(buf)
	return err
}

func readFrame(r *bufio.Reader) (typ byte, flow uint16, payload []byte, err error) {
	var header [4]byte
	if _, err = io.ReadFull(r, header[:]); err != nil {
		return 0, 0, nil, err
	}
	size := binary.BigEndian.Uint32(header[:])
	if size < 3 || size > maxFrameSize {
		return 0, 0, nil, fmt.Errorf("frame inválido: %d bytes", size)
	}
	body := make([]byte, size)
	if _, err = io.ReadFull(r, body); err != nil {
		return 0, 0, nil, err
	}
	return body[0], binary.BigEndian.Uint16(body[1:3]), body[3:], nil
}

// readLine reads a short ASCII line (the handshake reply) without buffering
// more than the line itself.
func readLine(conn net.Conn) (string, error) {
	var sb strings.Builder
	var one [1]byte
	for i := 0; i < 512; i++ {
		if _, err := io.ReadFull(conn, one[:]); err != nil {
			return "", err
		}
		if one[0] == '\n' {
			return sb.String(), nil
		}
		sb.WriteByte(one[0])
	}
	return "", fmt.Errorf("linha de handshake muito longa")
}

// newFrameReader wraps a connection in the buffered reader used by readFrame.
func newFrameReader(r io.Reader) *bufio.Reader { return bufio.NewReader(r) }
