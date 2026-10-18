package com.lamadriguera.app.domain

import com.lamadriguera.app.domain.model.AccountKind
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.UserCapabilities
import com.lamadriguera.app.domain.model.UserStatus
import com.lamadriguera.app.domain.model.VerificationLevel
import com.lamadriguera.app.domain.usecase.AliasGenerator
import com.lamadriguera.app.domain.usecase.IdentityVerificationForm
import com.lamadriguera.app.domain.usecase.OtpCode
import com.lamadriguera.app.domain.usecase.PhoneNumber
import com.lamadriguera.app.domain.usecase.ProfileDefaults
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasGeneratorTest {

    private val generator = AliasGenerator()

    @Test
    fun anonWithoutAliasGetsGeneratedAlias() {
        val alias = generator("user-1", PrivacyLevel.ANON, null)
        assertEquals(AliasGenerator.PREFIX, alias?.take(AliasGenerator.PREFIX.length))
        assertEquals(6, alias?.substringAfter(AliasGenerator.PREFIX)?.length)
    }

    @Test
    fun anonWithBlankAliasGetsGeneratedAlias() {
        val alias = generator("user-1", PrivacyLevel.ANON, "   ")
        assertEquals(AliasGenerator.PREFIX, alias?.take(AliasGenerator.PREFIX.length))
    }

    @Test
    fun generatedAliasIsStablePerUser() {
        assertEquals(generator.generate("user-1"), generator.generate("user-1"))
    }

    @Test
    fun explicitAliasSurvivesAnon() {
        assertEquals("MiAlias", generator("user-1", PrivacyLevel.ANON, " MiAlias "))
    }

    @Test
    fun publicProfileKeepsNullAlias() {
        assertNull(generator("user-1", PrivacyLevel.PUBLIC, null))
    }

    @Test
    fun generatedAliasIsUpperHex() {
        assertTrue(Regex("Anon-[0-9A-F]{6}").matches(generator.generate("00000000-0000-0000-0000-000000000001")))
    }
}

class PhoneNumberTest {

    @Test
    fun cubanNationalNumberBecomesE164() {
        assertEquals("+5351234567", PhoneNumber.normalize("51234567"))
        assertEquals("+5351234567", PhoneNumber.normalize("5123 4567"))
    }

    @Test
    fun internationalPrefixIsAccepted() {
        assertEquals("+5351234567", PhoneNumber.normalize("005351234567"))
        assertEquals("+5351234567", PhoneNumber.normalize("+53 51234567"))
    }

    @Test
    fun alreadyE164WithCountryCodeIsKept() {
        assertEquals("+14155552671", PhoneNumber.normalize("+1 415 555 2671"))
    }

    @Test
    fun invalidNumbersAreRejected() {
        assertNull(PhoneNumber.normalize(""))
        assertNull(PhoneNumber.normalize("abc"))
        assertNull(PhoneNumber.normalize("+0123"))
        assertNull(PhoneNumber.normalize("12"))
    }
}

class OtpCodeTest {

    @Test
    fun sixDigitsAreValid() {
        assertTrue(OtpCode.isValid("123456"))
        assertTrue(OtpCode.isValid(" 123456 "))
    }

    @Test
    fun wrongLengthOrLettersAreInvalid() {
        assertFalse(OtpCode.isValid("12345"))
        assertFalse(OtpCode.isValid("1234567"))
        assertFalse(OtpCode.isValid("12345a"))
    }
}

class IdentityVerificationFormTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 17)

    @Test
    fun fullNameMustBePresent() {
        assertNull(IdentityVerificationForm.validateFullName("Nombre Apellido"))
        assertTrue(IdentityVerificationForm.validateFullName("  ") != null)
    }

    @Test
    fun birthdateMustBeAdultAndPast() {
        assertNull(IdentityVerificationForm.validateBirthdate(LocalDate.of(1990, 1, 1), today))
        assertTrue(IdentityVerificationForm.validateBirthdate(null, today) != null)
        assertTrue(IdentityVerificationForm.validateBirthdate(today.plusDays(1), today) != null)
        assertTrue(
            IdentityVerificationForm.validateBirthdate(today.minusYears(13), today) != null
        )
        assertNull(IdentityVerificationForm.validateBirthdate(today.minusYears(14), today))
    }
}

class UserCapabilitiesTest {

    private fun user(
        status: UserStatus = UserStatus.TEMP,
        level: VerificationLevel = VerificationLevel.PHONE
    ) = User(
        id = "u",
        phone = "+5351234567",
        verificationLevel = level,
        status = status,
        createdAt = java.time.Instant.parse("2026-10-17T12:00:00Z")
    )

    @Test
    fun tempUserCanNavigateButNotCreateGroupsOrBet() {
        val caps = UserCapabilities.of(user(), AccountKind.PRIMARY)
        assertTrue(caps.canNavigate)
        assertTrue(caps.isTemp)
        assertFalse(caps.canCreateGroups)
        assertFalse(caps.canBet)
    }

    @Test
    fun primaryAccountMayAuctionWithPhoneButBettingStillRequiresIdentity() {
        val caps = UserCapabilities.of(user(), AccountKind.PRIMARY)
        assertTrue(caps.canAuction)
        assertFalse(caps.canBet)
    }

    @Test
    fun secondaryAccountNeedsIdentityForAuctionsAndBets() {
        val without = UserCapabilities.of(user(), AccountKind.SECONDARY)
        assertFalse(without.canAuction)
        assertFalse(without.canBet)
        val with = UserCapabilities.of(
            user(UserStatus.ACTIVE, VerificationLevel.IDENTITY),
            AccountKind.SECONDARY
        )
        assertTrue(with.canAuction)
        assertTrue(with.canBet)
        assertTrue(with.canCreateGroups)
    }

    @Test
    fun activeIdentityAccountHasFullAccess() {
        val caps = UserCapabilities.of(
            user(UserStatus.ACTIVE, VerificationLevel.IDENTITY),
            AccountKind.PRIMARY
        )
        assertTrue(caps.canNavigate)
        assertTrue(caps.canCreateGroups)
        assertTrue(caps.canBet)
        assertTrue(caps.canAuction)
    }

    @Test
    fun bannedOrSuspendedCannotNavigate() {
        assertFalse(
            UserCapabilities.of(user(UserStatus.BANNED), AccountKind.PRIMARY).canNavigate
        )
        assertFalse(
            UserCapabilities.of(user(UserStatus.SUSPENDED), AccountKind.PRIMARY).canNavigate
        )
    }
}

class ProfileDefaultsTest {

    @Test
    fun displayNameUsesLastPhoneDigits() {
        assertEquals("Usuario 4567", ProfileDefaults.displayNameFor("+5351234567"))
        assertEquals("Usuario", ProfileDefaults.displayNameFor(null))
    }
}
