package main

import (
	"math"
	"strings"
	"sync"

	"github.com/df-mc/dragonfly/server/block"
	"github.com/df-mc/dragonfly/server/block/cube"
	"github.com/df-mc/dragonfly/server/world"
	"github.com/df-mc/dragonfly/server/world/biome"
	"github.com/df-mc/dragonfly/server/world/chunk"
)

// terrainSeed keeps generated worlds reproducible: the same phone always
// produces the same landscape, and the spawn position matches the terrain.
const terrainSeed = uint64(0x4D4F424F53)

// seaLevel is the Y level at which oceans and lakes are filled with water.
const seaLevel = int16(63)

// terrain is a lightweight overworld generator. It only runs for chunks that
// are not present in the world provider, so imported (vanilla) worlds are
// never modified. It uses no external dependencies and generates a chunk in a
// couple of milliseconds on a phone.
type terrain struct {
	once sync.Once

	stone, dirt, grass, bedrock, sand, water, gravel, coal, iron, log, leaves uint32
	plains                                                                  uint32
}

func newTerrain() *terrain { return &terrain{} }

func (t *terrain) resolve() {
	br := world.DefaultBlockRegistry
	t.stone = br.BlockRuntimeID(block.Stone{})
	t.dirt = br.BlockRuntimeID(block.Dirt{})
	t.grass = br.BlockRuntimeID(block.Grass{})
	t.bedrock = br.BlockRuntimeID(block.Bedrock{})
	t.sand = br.BlockRuntimeID(block.Sand{})
	t.water = br.BlockRuntimeID(block.Water{Depth: 8, Still: true})
	t.gravel = br.BlockRuntimeID(block.Gravel{})
	t.coal = br.BlockRuntimeID(block.CoalOre{})
	t.iron = br.BlockRuntimeID(block.IronOre{})
	t.log = br.BlockRuntimeID(block.Log{Wood: block.OakWood(), Axis: cube.Y})
	t.leaves = br.BlockRuntimeID(block.Leaves{Type: block.OakLeaves(), Persistent: true})
	t.plains = uint32(biome.Plains{}.EncodeBiome())
}

// GenerateChunk implements world.Generator. It is safe to call concurrently.
func (t *terrain) GenerateChunk(pos world.ChunkPos, c *chunk.Chunk) {
	t.once.Do(t.resolve)
	r := c.Range()
	min, max := int16(r.Min()), int16(r.Max())

	for x := uint8(0); x < 16; x++ {
		for z := uint8(0); z < 16; z++ {
			wx := int64(pos[0])*16 + int64(x)
			wz := int64(pos[1])*16 + int64(z)
			h := clampHeight(surfaceHeight(wx, wz), min, max)

			for y := min; y <= max; y++ {
				id := t.blockAt(wx, wz, y, h, min)
				if id != 0 {
					c.SetBlock(x, y, z, 0, id)
				}
				c.SetBiome(x, y, z, t.plains)
			}
			t.maybeTree(c, x, z, h, wx, wz)
		}
	}
}

// DefaultSpawn implements world.Generator.
func (t *terrain) DefaultSpawn(world.Dimension) cube.Pos {
	return cube.Pos{0, int(surfaceHeight(0, 0)) + 2, 0}
}

func (t *terrain) blockAt(wx, wz int64, y, h, min int16) uint32 {
	switch {
	case y == min:
		return t.bedrock
	case y <= h-4:
		if ore := t.ore(wx, int64(y), wz); ore != 0 {
			return ore
		}
		return t.stone
	case y < h:
		return t.dirt
	case y == h:
		switch {
		case h <= seaLevel+1:
			return t.sand
		case h >= 100:
			return t.stone
		default:
			return t.grass
		}
	case y <= seaLevel:
		return t.water
	}
	return 0
}

func (t *terrain) ore(wx, y, wz int64) uint32 {
	v := hash3(wx, y, wz, terrainSeed^0xABCDEF19)
	switch {
	case v < 0.010:
		return t.coal
	case v < 0.016:
		return t.iron
	}
	return 0
}

// maybeTree plants an oak tree. Trees are only placed well inside the chunk so
// leaves never spill into a neighbouring chunk that may not be generated yet.
func (t *terrain) maybeTree(c *chunk.Chunk, x, z uint8, h int16, wx, wz int64) {
	if x < 3 || x > 12 || z < 3 || z > 12 {
		return
	}
	if h <= seaLevel+2 || h >= 100 {
		return
	}
	if hash2(wx, wz, terrainSeed^0x5F5E) > 0.010 {
		return
	}
	max := int16(c.Range().Max())
	trunk := int16(4) + int16(hash2(wx, wz, terrainSeed^0x77)*3)
	top := h + trunk
	if top+2 > max {
		return
	}
	for y := h + 1; y <= top; y++ {
		if id := c.Block(x, y, z, 0); id != 0 && id != t.leaves {
			return
		}
		c.SetBlock(x, y, z, 0, t.log)
	}
	for dy := int16(-2); dy <= 1; dy++ {
		radius := int16(2)
		if dy == 1 {
			radius = 1
		}
		for dx := -radius; dx <= radius; dx++ {
			for dz := -radius; dz <= radius; dz++ {
				if dx == 0 && dz == 0 && dy <= 0 {
					continue
				}
				if dx == dz && (dx == radius || dx == -radius) {
					continue
				}
				lx, lz := int(x)+int(dx), int(z)+int(dz)
				if lx < 0 || lx > 15 || lz < 0 || lz > 15 {
					continue
				}
				ly := top + dy
				if id := c.Block(uint8(lx), ly, uint8(lz), 0); id != 0 && id != t.leaves {
					continue
				}
				c.SetBlock(uint8(lx), ly, uint8(lz), 0, t.leaves)
			}
		}
	}
}

// surfaceHeight returns the top solid block Y at a world column. It is a pure
// function so the spawn point always matches the generated terrain.
func surfaceHeight(wx, wz int64) int16 {
	const freq = 1.0 / 118.0
	base := fbm(float64(wx)*freq, float64(wz)*freq, 4, terrainSeed)
	detail := fbm(float64(wx)*freq*4.3+31.7, float64(wz)*freq*4.3-11.3, 2, terrainSeed^0x1234)
	h := 68.0 + (base-0.5)*34.0 + (detail-0.5)*10.0

	// Spawn island: the area around 0,0 is blended towards a dry, flat plateau
	// so every player always starts on solid ground, even when the surrounding
	// landscape is an ocean.
	if d := math.Hypot(float64(wx), float64(wz)); d < 64 {
		w := 1 - smoothStep(d/64)
		h = h*(1-w) + 70*w
	}
	return int16(math.Round(h))
}

func clampHeight(h, min, max int16) int16 {
	if h < min+3 {
		return min + 3
	}
	if h > max-6 {
		return max - 6
	}
	return h
}

// defaultSpawn mirrors the generator so brand new worlds start on solid ground.
func defaultSpawn(gen string, dim world.Dimension) cube.Pos {
	if strings.EqualFold(gen, "flat") {
		return cube.Pos{0, dim.Range().Min() + 5, 0}
	}
	if dim == world.Overworld {
		return cube.Pos{0, int(surfaceHeight(0, 0)) + 2, 0}
	}
	return cube.Pos{0, dim.Range().Min() + 5, 0}
}

// hash2 and hash3 are deterministic integer hashes used for value noise and
// for scatter decisions (ores, trees).
func hash2(x, z int64, seed uint64) float64 {
	return hashU64(uint64(x)*0x9E3779B97F4A7C15^uint64(z)*0xC2B2AE3D27D4EB4F^seed) 
}

func hash3(x, y, z int64, seed uint64) float64 {
	return hashU64(uint64(x)*0x9E3779B97F4A7C15 ^ uint64(y)*0x165667B19E3779F9 ^ uint64(z)*0xC2B2AE3D27D4EB4F ^ seed)
}

func hashU64(h uint64) float64 {
	h ^= h >> 30
	h *= 0xBF58476D1CE4E5B9
	h ^= h >> 27
	h *= 0x94D049BB133111EB
	h ^= h >> 31
	return float64(h>>11) / float64(uint64(1)<<53)
}

func smoothStep(t float64) float64 { return t * t * (3 - 2*t) }

func valueNoise(x, z float64, seed uint64) float64 {
	x0, z0 := math.Floor(x), math.Floor(z)
	tx, tz := smoothStep(x-x0), smoothStep(z-z0)
	ix, iz := int64(x0), int64(z0)

	c00 := hash2(ix, iz, seed)
	c10 := hash2(ix+1, iz, seed)
	c01 := hash2(ix, iz+1, seed)
	c11 := hash2(ix+1, iz+1, seed)

	a := c00 + (c10-c00)*tx
	b := c01 + (c11-c01)*tx
	return a + (b-a)*tz
}

func fbm(x, z float64, octaves int, seed uint64) float64 {
	amp, freq, sum, norm := 1.0, 1.0, 0.0, 0.0
	for i := 0; i < octaves; i++ {
		sum += amp * valueNoise(x*freq, z*freq, seed+uint64(i)*0x9E3779B9)
		norm += amp
		amp *= 0.5
		freq *= 2.0
	}
	return sum / norm
}
