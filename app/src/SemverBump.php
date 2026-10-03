<?php

declare(strict_types=1);

namespace App;

use InvalidArgumentException;

/**
 * Computes the next MAJOR.MINOR.PATCH version. Pre-release and build metadata are not supported.
 */
final class SemverBump
{
    // Each part is capped at 9 digits so that a bump can never overflow an int.
    private const string PATTERN = '/^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/D';

    public static function bump(string $current, string $bump): string
    {
        if (preg_match(self::PATTERN, $current, $parts) !== 1) {
            throw new InvalidArgumentException(sprintf('Invalid version "%s": expected MAJOR.MINOR.PATCH', $current));
        }

        [$major, $minor, $patch] = array_map(intval(...), array_slice($parts, 1));

        return match ($bump) {
            'major' => sprintf('%d.0.0', $major + 1),
            'minor' => sprintf('%d.%d.0', $major, $minor + 1),
            'patch' => sprintf('%d.%d.%d', $major, $minor, $patch + 1),
            default => throw new InvalidArgumentException(
                sprintf('Invalid bump "%s": expected major, minor or patch', $bump),
            ),
        };
    }
}
