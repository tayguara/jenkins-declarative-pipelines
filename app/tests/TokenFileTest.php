<?php

declare(strict_types=1);

namespace App\Tests;

use App\TokenFile;
use PHPUnit\Framework\Attributes\CoversClass;
use PHPUnit\Framework\TestCase;

#[CoversClass(TokenFile::class)]
final class TokenFileTest extends TestCase
{
    private string $file;

    protected function setUp(): void
    {
        $this->file = sys_get_temp_dir() . '/token-file-' . bin2hex(random_bytes(6));
    }

    protected function tearDown(): void
    {
        @unlink($this->file);
    }

    public function testReadsTheTokenWithoutTrailingNewline(): void
    {
        file_put_contents($this->file, "s3cret-value\n");

        self::assertSame('s3cret-value', TokenFile::read($this->file));
    }

    public function testIsNullWhenNoPathIsConfigured(): void
    {
        self::assertNull(TokenFile::read(null));
        self::assertNull(TokenFile::read(''));
    }

    public function testIsNullWhenTheFileDoesNotExist(): void
    {
        self::assertNull(TokenFile::read($this->file));
    }

    public function testIsNullWhenTheFileIsBlank(): void
    {
        file_put_contents($this->file, " \n");

        self::assertNull(TokenFile::read($this->file));
    }
}
