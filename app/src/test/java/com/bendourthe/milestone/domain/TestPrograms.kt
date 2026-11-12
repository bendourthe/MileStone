package com.bendourthe.milestone.domain

fun testProgram(
    id: String,
    name: String = id.replaceFirstChar(Char::uppercase),
    category: ProgramCategory = ProgramCategory.HOTEL,
    capability: SyncCapability = SyncCapability.ManualOnly("test"),
) = Program(
    programId = id,
    displayName = name,
    category = category,
    monogram = id.take(2).uppercase(),
    logoKey = null,
    isPopular = false,
    brandColorArgb = 0xFF336699,
    unitLabel = "pts",
    loginUrl = "https://www.$id.com/login",
    dashboardUrl = "https://www.$id.com/account",
    allowedHosts = setOf("$id.com"),
    identityProviderHosts = emptySet(),
    syncCapability = capability,
)

class FakeCatalog(override val programs: List<Program>) : CatalogRepository {
    override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
    override fun search(query: String) = CatalogSearch.filter(programs, query)
}
