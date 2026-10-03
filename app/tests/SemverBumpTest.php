<?php

declare(strict_types=1);

namespace App\Tests;

use App\SemverBump;
use InvalidArgumentException;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

#[CoversClass(SemverBump::class)]
final class SemverBumpTest extends TestCase
{
    /**
     * @return iterable<string, array{string, string, string}>
     */
    public static function validBumps(): iterable
    {
        yield 'patch' => ['1.2.3', 'patch', '1.2.4'];
        yield 'minor resets patch' => ['1.2.3', 'minor', '1.3.0'];
        yield 'major resets minor and patch' => ['1.2.3', 'major', '2.0.0'];
        yield 'from zero' => ['0.0.0', 'patch', '0.0.1'];
        yield 'multi-digit parts' => ['10.20.30', 'minor', '10.21.0'];
        yield 'largest accepted part' => ['999999999.0.0', 'patch', '999999999.0.1'];
    }

    #[DataProvider('validBumps')]
    public function testBumpsTheRequestedPart(string $current, string $bump, string $expected): void
    {
        self::assertSame($expected, SemverBump::bump($current, $bump));
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function invalidVersions(): iterable
    {
        yield 'empty' => [''];
        yield 'two parts' => ['1.2'];
        yield 'four parts' => ['1.2.3.4'];
        yield 'leading v' => ['v1.2.3'];
        yield 'leading zero' => ['01.2.3'];
        yield 'pre-release suffix' => ['1.2.3-rc.1'];
        yield 'letters' => ['a.b.c'];
        yield 'negative part' => ['1.-2.3'];
        yield 'surrounding whitespace' => [' 1.2.3 '];
        yield 'trailing newline' => ["1.2.3\n"];
        yield 'part too large' => ['1000000000.0.0'];
    }

    #[DataProvider('invalidVersions')]
    public function testRejectsInvalidVersions(string $current): void
    {
        $this->expectException(InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid version');

        SemverBump::bump($current, 'patch');
    }

    public function testRejectsUnknownBumpType(): void
    {
        $this->expectException(InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid bump "huge": expected major, minor or patch');

        SemverBump::bump('1.2.3', 'huge');
    }
}
