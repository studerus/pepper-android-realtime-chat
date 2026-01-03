package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.data.ConditionOperator
import ch.fhnw.pepper_realtime.data.EventRule
import ch.fhnw.pepper_realtime.data.MatchedRule
import ch.fhnw.pepper_realtime.data.PersonEventType
import ch.fhnw.pepper_realtime.data.RuleActionType
import ch.fhnw.pepper_realtime.data.RuleCondition
import ch.fhnw.pepper_realtime.data.RulePersistence
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for EventRulesManager.
 * Tests CRUD operations, state management, and callback handling.
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class EventRulesManagerTest {

    @Mock
    private lateinit var mockEventRuleEngine: EventRuleEngine

    @Mock
    private lateinit var mockRulePersistence: RulePersistence

    private lateinit var manager: EventRulesManager

    private val testRule = EventRule(
        id = "test-rule-1",
        name = "Test Rule",
        enabled = true,
        eventType = PersonEventType.PERSON_RECOGNIZED,
        conditions = listOf(
            RuleCondition("personName", ConditionOperator.EQUALS, "John")
        ),
        actionType = RuleActionType.APPEND_AND_RESPOND,
        template = "Hello {personName}!",
        cooldownMs = 5000L
    )

    private val testRule2 = EventRule(
        id = "test-rule-2",
        name = "Test Rule 2",
        enabled = false,
        eventType = PersonEventType.PERSON_APPEARED,
        conditions = emptyList(),
        actionType = RuleActionType.SILENT_UPDATE,
        template = "New person detected",
        cooldownMs = 10000L
    )

    @Before
    fun setUp() {
        manager = EventRulesManager(mockEventRuleEngine, mockRulePersistence)
    }

    // ==================== Initialization Tests ====================

    @Test
    fun `initialize loads rules from persistence`() {
        // Arrange
        val savedRules = listOf(testRule, testRule2)
        whenever(mockRulePersistence.loadRules()).thenReturn(savedRules)

        // Act
        manager.initialize()

        // Assert
        verify(mockRulePersistence).loadRules()
        verify(mockEventRuleEngine).loadRules(savedRules)
        assertEquals(2, manager.state.value.rules.size)
    }

    @Test
    fun `initialize sets up rule match listener`() {
        // Arrange
        whenever(mockRulePersistence.loadRules()).thenReturn(emptyList())

        // Act
        manager.initialize()

        // Assert
        verify(mockEventRuleEngine).setListener(any())
    }

    // ==================== UI State Tests ====================

    @Test
    fun `showEventRules sets visibility to true`() {
        // Act
        manager.showEventRules()

        // Assert
        assertTrue(manager.state.value.isVisible)
    }

    @Test
    fun `hideEventRules sets visibility to false`() {
        // Arrange
        manager.showEventRules()
        assertTrue(manager.state.value.isVisible)

        // Act
        manager.hideEventRules()

        // Assert
        assertFalse(manager.state.value.isVisible)
    }

    @Test
    fun `toggleEventRules flips visibility`() {
        // Initial state should be not visible
        assertFalse(manager.state.value.isVisible)

        // First toggle -> visible
        manager.toggleEventRules()
        assertTrue(manager.state.value.isVisible)

        // Second toggle -> not visible
        manager.toggleEventRules()
        assertFalse(manager.state.value.isVisible)
    }

    // ==================== CRUD Tests ====================

    @Test
    fun `addEventRule adds rule and persists`() {
        // Arrange
        whenever(mockEventRuleEngine.getRules()).thenReturn(listOf(testRule))

        // Act
        manager.addEventRule(testRule)

        // Assert
        verify(mockEventRuleEngine).addRule(testRule)
        verify(mockRulePersistence).saveRules(listOf(testRule))
        assertEquals(1, manager.state.value.rules.size)
    }

    @Test
    fun `updateEventRule updates rule and persists`() {
        // Arrange
        val updatedRule = testRule.copy(name = "Updated Rule Name")
        whenever(mockEventRuleEngine.getRules()).thenReturn(listOf(updatedRule))

        // Act
        manager.updateEventRule(updatedRule)

        // Assert
        verify(mockEventRuleEngine).updateRule(updatedRule)
        verify(mockRulePersistence).saveRules(listOf(updatedRule))
    }

    @Test
    fun `deleteEventRule removes rule and persists`() {
        // Arrange
        whenever(mockEventRuleEngine.getRules()).thenReturn(emptyList())

        // Act
        manager.deleteEventRule("test-rule-1")

        // Assert
        verify(mockEventRuleEngine).removeRule("test-rule-1")
        verify(mockRulePersistence).saveRules(emptyList())
    }

    @Test
    fun `toggleEventRuleEnabled flips enabled status`() {
        // Arrange
        val rulesWithEnabled = listOf(testRule) // testRule.enabled = true
        whenever(mockEventRuleEngine.getRules()).thenReturn(rulesWithEnabled)

        // Act
        manager.toggleEventRuleEnabled("test-rule-1")

        // Assert
        verify(mockEventRuleEngine).updateRule(testRule.copy(enabled = false))
    }

    @Test
    fun `toggleEventRuleEnabled does nothing for unknown rule`() {
        // Arrange
        whenever(mockEventRuleEngine.getRules()).thenReturn(emptyList())

        // Act
        manager.toggleEventRuleEnabled("unknown-rule")

        // Assert
        verify(mockEventRuleEngine, never()).updateRule(any())
    }

    // ==================== Import/Export Tests ====================

    @Test
    fun `exportEventRules delegates to persistence`() {
        // Arrange
        val expectedJson = """[{"id":"test"}]"""
        whenever(mockRulePersistence.exportToJson()).thenReturn(expectedJson)

        // Act
        val result = manager.exportEventRules()

        // Assert
        assertEquals(expectedJson, result)
        verify(mockRulePersistence).exportToJson()
    }

    @Test
    fun `importEventRules loads and updates state`() {
        // Arrange
        val json = """[{"id":"test"}]"""
        whenever(mockRulePersistence.importFromJson(json, false)).thenReturn(1)
        whenever(mockRulePersistence.loadRules()).thenReturn(listOf(testRule))
        whenever(mockEventRuleEngine.getRules()).thenReturn(listOf(testRule))

        // Act
        val count = manager.importEventRules(json)

        // Assert
        assertEquals(1, count)
        verify(mockRulePersistence).importFromJson(json, false)
        verify(mockEventRuleEngine).loadRules(listOf(testRule))
    }

    @Test
    fun `importEventRules returns negative on error`() {
        // Arrange
        whenever(mockRulePersistence.importFromJson(any(), any())).thenReturn(-1)

        // Act
        val count = manager.importEventRules("invalid json")

        // Assert
        assertEquals(-1, count)
        verify(mockEventRuleEngine, never()).loadRules(any())
    }

    // ==================== Reset Tests ====================

    @Test
    fun `resetEventRulesToDefaults resets persistence and engine`() {
        // Arrange
        val defaultRules = listOf(testRule)
        whenever(mockRulePersistence.loadRules()).thenReturn(defaultRules)

        // Act
        manager.resetEventRulesToDefaults()

        // Assert
        verify(mockRulePersistence).resetToDefaults()
        verify(mockEventRuleEngine).loadRules(defaultRules)
        verify(mockEventRuleEngine).resetCooldowns()
    }

    // ==================== Editing State Tests ====================

    @Test
    fun `setEditingRule updates state with rule`() {
        // Act
        manager.setEditingRule(testRule)

        // Assert
        assertEquals(testRule, manager.state.value.editingRule)
    }

    @Test
    fun `setEditingRule with null clears editing state`() {
        // Arrange
        manager.setEditingRule(testRule)
        assertNotNull(manager.state.value.editingRule)

        // Act
        manager.setEditingRule(null)

        // Assert
        assertNull(manager.state.value.editingRule)
    }

    // ==================== Engine Access Test ====================

    @Test
    fun `engine property exposes eventRuleEngine`() {
        // Assert
        assertEquals(mockEventRuleEngine, manager.engine)
    }
}
