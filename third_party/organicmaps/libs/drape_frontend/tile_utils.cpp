#include "drape_frontend/tile_utils.hpp"

#include "indexer/scales.hpp"

#include "geometry/mercator.hpp"

#include <algorithm>

namespace df
{
CoverageResult CalcTilesCoverage(m2::RectD const & rect, int targetZoom,
                                 std::function<void(int, int)> const & processTile)
{
  int const safeTargetZoom = std::max(targetZoom, 1);
  double const rectSize = mercator::Bounds::kRangeX / (1 << (safeTargetZoom - 1));

  CoverageResult result;
  result.m_minTileX = static_cast<int>(floor(rect.minX() / rectSize));
  result.m_maxTileX = static_cast<int>(ceil(rect.maxX() / rectSize));
  result.m_minTileY = static_cast<int>(floor(rect.minY() / rectSize));
  result.m_maxTileY = static_cast<int>(ceil(rect.maxY() / rectSize));

  if (processTile)
    result.ForEach(processTile);

  return result;
}

bool IsNeighbours(TileKey const & tileKey1, TileKey const & tileKey2)
{
  return !((tileKey1.m_x == tileKey2.m_x) && (tileKey1.m_y == tileKey2.m_y)) &&
         (abs(tileKey1.m_x - tileKey2.m_x) < 2) && (abs(tileKey1.m_y - tileKey2.m_y) < 2);
}

int ClipTileZoomByMaxDataZoom(int zoom)
{
  return std::min(zoom, scales::GetUpperScale());
}

TileKey GetTileKeyByPoint(m2::PointD const & pt, int zoom)
{
  int const safeZoom = std::max(zoom, 1);
  double const rectSize = mercator::Bounds::kRangeX / (1 << (safeZoom - 1));
  return TileKey(static_cast<int>(floor(pt.x / rectSize)), static_cast<int>(floor(pt.y / rectSize)), safeZoom);
}
}  // namespace df
